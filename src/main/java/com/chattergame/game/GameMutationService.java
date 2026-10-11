package com.chattergame.game;

import com.chattergame.journal.GameEventRepository;
import com.chattergame.journal.GameEventType;
import com.chattergame.journal.Payloads;
import com.chattergame.util.ApiException;
import com.chattergame.util.Uuids;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Единственное писательское горло на игру (`D-034`). Каждая мутация мира проходит здесь:
 * сервис читает текущее состояние, проверяет право мастера, применяет переход и пишет
 * событие в журнал (`D-033`). Контроллеры доменных таблиц не пишут вообще.
 *
 * <p>Порядок внутри транзакции: сначала эффект, затем событие. Так событие оказывается
 * на последнем выданном `seq`, и аудит, читающий по возрастанию номера, видит переход
 * после того, как он случился, а `last_seq` совпадает с максимальным `seq` журнала.
 *
 * <p>Транзакция — `MANDATORY` у внутренней записи и обычная у публичных операций: сервис
 * вызывают и из HTTP, и из {@code AccountDeletionService}, и в обоих случаях эффект с
 * событием обязаны попасть в одну транзакцию. Блокирующих внешних вызовов здесь нет
 * по построению: LLM, SMTP и HTTP запрещены внутри транзакции (`D-044`).
 */
@Service
public class GameMutationService {

    private final GameRepository games;
    private final MembershipRepository memberships;
    private final GameEventRepository events;
    private final Payloads payloads;
    private final ReadinessValidation readiness;

    public GameMutationService(
            GameRepository games,
            MembershipRepository memberships,
            GameEventRepository events,
            Payloads payloads,
            ReadinessValidation readiness) {
        this.games = games;
        this.memberships = memberships;
        this.events = events;
        this.payloads = payloads;
        this.readiness = readiness;
    }

    // --- Создание и содержимое --------------------------------------------------------

    /**
     * Создание игры. Мастер становится её участником сразу со статусом `JOINED`: приглашать
     * его некому, а без участия он не пройдёт ни одну проверку прав (`REQUIREMENTS §6`).
     */
    @Transactional
    public Game createGame(UUID masterUserId, String title, String publicDescription, String privateDescription) {
        requireText(title, "title_required", "Укажите название игры", Game.TITLE_MAX);

        UUID gameId = Uuids.v7();
        Instant now = Instant.now();
        GameState state = new GameState(
                gameId, Readiness.DRAFT, Visibility.PRIVATE, Phase.NOT_STARTED, false, null, false);
        Game game = new Game(
                gameId,
                masterUserId,
                title.strip(),
                normalize(publicDescription),
                normalize(privateDescription),
                state,
                false,
                false,
                null,
                null,
                now,
                now);

        games.insert(game);
        memberships.insert(Membership.master(Uuids.v7(), gameId, masterUserId, now));
        // Счётчик создаётся вместе с игрой: тогда выдача номера — только блокировка строки,
        // а не вставка, которая на параллельных мутациях блокирует соседей.
        events.createStream(gameId);
        event(gameId, GameEventType.GAME_CREATED, masterUserId, payload("title", game.title()));
        return game;
    }

    /** Правка названия и описаний. После `FINISHED` запрещена: транскрипт — артефакт (`D-094`). */
    @Transactional
    public Game updateContent(
            UUID actorUserId, UUID gameId, String title, String publicDescription, String privateDescription) {
        Game game = requireMaster(actorUserId, gameId);
        requireNotFinished(game, "content_change_after_finish", "После завершения игры описания не меняются");
        requireText(title, "title_required", "Укажите название игры", Game.TITLE_MAX);

        games.updateContent(
                gameId, title.strip(), normalize(publicDescription), normalize(privateDescription));
        event(gameId, GameEventType.GAME_CONTENT_CHANGED, actorUserId, payload("title", title.strip()));
        return requireGame(gameId);
    }

    /** Видимость не привязана к фазе: игру можно открыть и закрыть в любой момент (`D-091`). */
    @Transactional
    public Game changeVisibility(UUID actorUserId, UUID gameId, Visibility visibility) {
        requireMaster(actorUserId, gameId);
        if (visibility == null) {
            throw ApiException.badRequest("visibility_required", "Укажите видимость игры");
        }

        games.updateVisibility(gameId, visibility);
        event(gameId, GameEventType.GAME_CONTENT_CHANGED, actorUserId,
                payload("visibility", visibility.name()));
        return requireGame(gameId);
    }

    // --- Переходы состояния -----------------------------------------------------------

    /**
     * Общая точка перехода. Право мастера проверяется здесь, а не в контроллере: переход
     * приходит и из будущих заявок (`E14`), и проверка обязана быть одна.
     */
    @Transactional
    public Game applyTransition(UUID actorUserId, UUID gameId, GameTransition transition, String pauseReason) {
        Game game = requireMaster(actorUserId, gameId);
        if (transition == GameTransition.MARK_READY) {
            ReadinessReport report = readiness.validate(gameId);
            if (readiness.blocksReady(report)) {
                throw ApiException.conflict(
                        "readiness_blocking",
                        "Готовность невозможна: " + report.blocking().get(0).message());
            }
        }
        if (transition == GameTransition.PAUSE) {
            requireText(pauseReason, "pause_reason_required", "Укажите причину паузы", 500);
        }

        GameState next = GameStateMachine.apply(game.state(), transition, pauseReason);
        games.updateState(next);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("from", game.state().readiness().name() + "/" + game.state().phase().name());
        payload.put("to", next.readiness().name() + "/" + next.phase().name());
        if (next.pauseReason() != null) {
            payload.put("reason", next.pauseReason());
        }
        event(gameId, eventTypeOf(transition), actorUserId, payload);
        return requireGame(gameId);
    }

    /**
     * Событие перехода. Пауза, возобновление и готовность имеют собственные типы: карта
     * «событие → канал» (`O03`) и фильтр аудита различают их по типу, а не по payload.
     */
    private static GameEventType eventTypeOf(GameTransition transition) {
        return switch (transition) {
            case MARK_READY, RETURN_TO_DRAFT -> GameEventType.GAME_READINESS_CHANGED;
            case START -> GameEventType.GAME_STARTED;
            case FINISH -> GameEventType.GAME_FINISHED;
            case PAUSE -> GameEventType.GAME_PAUSED;
            case RESUME -> GameEventType.GAME_RESUMED;
        };
    }

    /**
     * Архивирование. Единственный путь «снять игру с глаз»: удаление запрещено навсегда
     * (`D-010`). Идемпотентно — повторный вызов не пишет второе событие.
     */
    @Transactional
    public boolean archiveGame(UUID actorUserId, UUID gameId, String reason) {
        requireMaster(actorUserId, gameId);
        return archive(gameId, actorUserId, reason);
    }

    /**
     * Архивирование всех игр мастера при удалении его аккаунта (`D-095`). Проверки роли здесь
     * нет намеренно: мастер удаляет сам себя, и его участие к этому моменту уже анонимизировано,
     * а решение «архивировать свои игры» принято вызывающим слоем осознанно. `masterUserId`
     * остаётся в payload, потому что по нему аудит отвечает на вопрос «из-за чего игра в архиве».
     */
    @Transactional
    public int archiveAllOfMaster(UUID masterUserId, String reason) {
        requireText(reason, "archive_reason_required", "Укажите причину архивации", 500);

        int archived = 0;
        for (UUID gameId : games.findUnarchivedByMaster(masterUserId)) {
            if (archive(gameId, masterUserId, reason)) {
                archived++;
            }
        }
        return archived;
    }

    /**
     * Общая запись архивации. Повторный вызов на уже архивированной игре возвращает `false`
     * и не оставляет второго события: иначе повтор удаления аккаунта удвоил бы журнал.
     */
    private boolean archive(UUID gameId, UUID actorUserId, String reason) {
        Game game = games.findById(gameId)
                .orElseThrow(() -> ApiException.notFound("game_not_found", "Игра не найдена"));
        if (game.archived()) {
            return false;
        }
        requireText(reason, "archive_reason_required", "Укажите причину архивации", 500);

        games.markArchived(gameId, reason.strip());
        Map<String, Object> payload = payload("reason", reason.strip());
        payload.put("masterUserId", game.masterUserId().toString());
        event(gameId, GameEventType.GAME_ARCHIVED, actorUserId, payload);
        return true;
    }

    // --- Участие -----------------------------------------------------------------------

    /**
     * Заведение участия. На `E03` метод вызывается только для мастера при создании игры;
     * приглашения, занятие персонажа и наблюдатели принадлежат `E13`, но правило записи
     * («мутация оставляет событие») уже общее, поэтому точка входа одна и та же.
     */
    @Transactional
    public Membership addMembership(
            UUID actorUserId, UUID gameId, UUID userId, MembershipRole role, MembershipStatus status) {
        requireMaster(actorUserId, gameId);
        if (memberships.find(gameId, userId).isPresent()) {
            throw ApiException.conflict("membership_exists", "Участие в этой игре уже существует");
        }

        Instant now = Instant.now();
        Membership membership = new Membership(
                Uuids.v7(),
                gameId,
                userId,
                role,
                status,
                now,
                status == MembershipStatus.JOINED ? now : null,
                status == MembershipStatus.LEFT ? now : null);
        memberships.insert(membership);
        event(gameId, eventTypeOf(status), actorUserId, payload("userId", userId.toString()));
        return membership;
    }

    /** Смена статуса участия: вход, выход, бан. Строка не удаляется никогда (`D-010`). */
    @Transactional
    public Membership changeMembershipStatus(UUID actorUserId, UUID gameId, UUID userId, MembershipStatus status) {
        requireMaster(actorUserId, gameId);
        Membership current = memberships.find(gameId, userId)
                .orElseThrow(() -> ApiException.notFound("membership_not_found", "Участие не найдено"));
        if (current.role() == MembershipRole.MASTER) {
            throw ApiException.conflict(
                    "master_membership_immutable", "Статус участия мастера не меняется");
        }

        Instant now = Instant.now();
        Membership next = new Membership(
                current.id(),
                current.gameId(),
                current.userId(),
                current.role(),
                status,
                current.createdAt(),
                status == MembershipStatus.JOINED ? now : current.joinedAt(),
                status == MembershipStatus.LEFT ? now : current.leftAt());
        memberships.updateStatus(next);
        event(gameId, eventTypeOf(status), actorUserId, payload("userId", userId.toString()));
        return next;
    }

    private static GameEventType eventTypeOf(MembershipStatus status) {
        return switch (status) {
            case INVITED -> GameEventType.MEMBERSHIP_INVITED;
            case JOINED -> GameEventType.MEMBERSHIP_JOINED;
            case LEFT -> GameEventType.MEMBERSHIP_LEFT;
            case BANNED -> GameEventType.MEMBERSHIP_BANNED;
        };
    }

    // --- Общие проверки ----------------------------------------------------------------

    /** Проверка права мастера на каждую мутацию, а не один раз при установке соединения (`D-065`). */
    private Game requireMaster(UUID actorUserId, UUID gameId) {
        Game game = requireGame(gameId);
        Membership membership = memberships.find(gameId, actorUserId)
                .orElseThrow(() -> ApiException.forbidden("not_a_member", "Нет участия в этой игре"));
        if (membership.role() != MembershipRole.MASTER || membership.banned()) {
            throw ApiException.forbidden("not_master", "Изменять мир может только мастер");
        }
        return game;
    }

    private Game requireGame(UUID gameId) {
        return games.findById(gameId)
                .orElseThrow(() -> ApiException.notFound("game_not_found", "Игра не найдена"));
    }

    private static void requireNotFinished(Game game, String code, String message) {
        if (game.readOnly()) {
            throw ApiException.conflict(code, message);
        }
    }

    private static void requireText(String value, String code, String message, int maxLength) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(code, message);
        }
        if (value.strip().length() > maxLength) {
            throw ApiException.badRequest(code + "_too_long", message + ": до " + maxLength + " знаков");
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static Map<String, Object> payload(String key, Object value) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(key, value);
        return payload;
    }

    private void event(UUID gameId, GameEventType type, UUID actorUserId, Map<String, Object> payload) {
        events.append(gameId, type, actorUserId, null, null, false, payloads.write(payload));
    }

    /**
     * Точка, которой пользуются будущие сервисы внутри уже открытой транзакции: тогда событие
     * и эффект не могут разъехаться по разным транзакциям. `MANDATORY` выбран вместо
     * `REQUIRED`, чтобы отсутствие внешней транзакции падало, а не создавалось молча новое
     * соединение — «одно горло» держится на этом (`D-034`).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long appendEvent(UUID gameId, GameEventType type, UUID actorUserId, Map<String, Object> payload) {
        return events.append(gameId, type, actorUserId, null, null, false, payloads.write(payload));
    }
}
