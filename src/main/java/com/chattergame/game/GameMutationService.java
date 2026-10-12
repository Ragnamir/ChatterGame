package com.chattergame.game;

import com.chattergame.chat.Chat;
import com.chattergame.chat.ChatKind;
import com.chattergame.chat.ChatRepository;
import com.chattergame.chat.EdgeState;
import com.chattergame.chat.Transition;
import com.chattergame.chat.TransitionRepository;
import com.chattergame.chat.TransitionState;
import com.chattergame.chat.TransitionStateRepository;
import com.chattergame.character.Character;
import com.chattergame.character.CharacterRepository;
import com.chattergame.journal.GameEventRepository;
import com.chattergame.journal.GameEventType;
import com.chattergame.journal.Payloads;
import com.chattergame.media.Asset;
import com.chattergame.media.AssetRepository;
import com.chattergame.media.AssetService;
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
    private final ChatRepository chats;
    private final TransitionRepository transitions;
    private final TransitionStateRepository edgeStates;
    private final CharacterRepository characters;
    private final AssetRepository assets;
    private final AssetService assetService;

    public GameMutationService(
            GameRepository games,
            MembershipRepository memberships,
            GameEventRepository events,
            Payloads payloads,
            ReadinessValidation readiness,
            ChatRepository chats,
            TransitionRepository transitions,
            TransitionStateRepository edgeStates,
            CharacterRepository characters,
            AssetRepository assets,
            AssetService assetService) {
        this.games = games;
        this.memberships = memberships;
        this.events = events;
        this.payloads = payloads;
        this.readiness = readiness;
        this.chats = chats;
        this.transitions = transitions;
        this.edgeStates = edgeStates;
        this.characters = characters;
        this.assets = assets;
        this.assetService = assetService;
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

    // --- Локации и переходы (E04) ------------------------------------------------------

    /**
     * Создание локации (`REQUIREMENTS §7.1`). Работает и после `READY`: мастер достраивает
     * мир по ходу игры, и запрет на это в документах не заявлен.
     */
    @Transactional
    public Chat createLocation(UUID actorUserId, UUID gameId, String title, String description) {
        Game game = requireMaster(actorUserId, gameId);
        requireNotFinished(game, "content_change_after_finish", "После завершения игры мир не меняется");
        requireText(title, "title_required", "Укажите название локации", Chat.TITLE_MAX);

        Instant now = Instant.now();
        Chat chat = new Chat(
                Uuids.v7(),
                gameId,
                ChatKind.LOCATION,
                title.strip(),
                normalize(description),
                null,
                false,
                null,
                null,
                now);
        chats.insert(chat);
        Map<String, Object> payload = payload("chatId", chat.id().toString());
        payload.put("kind", chat.kind().name());
        payload.put("title", chat.title());
        event(gameId, GameEventType.CHAT_CREATED, actorUserId, payload);
        return chat;
    }

    /** Правка названия и описания локации. Посторонние поля остаются как были. */
    @Transactional
    public Chat updateLocation(
            UUID actorUserId, UUID gameId, UUID chatId, String title, String description) {
        Game game = requireMaster(actorUserId, gameId);
        requireNotFinished(game, "content_change_after_finish", "После завершения игры мир не меняется");
        Chat chat = requireLocation(gameId, chatId);
        requireText(title, "title_required", "Укажите название локации", Chat.TITLE_MAX);

        chats.updateContent(chatId, title.strip(), normalize(description));
        event(gameId, GameEventType.CHAT_CONTENT_CHANGED, actorUserId,
                payload("chatId", chatId.toString()));
        return chats.findById(chat.id()).orElseThrow();
    }

    /**
     * Архивирование локации. Единственный способ «убрать место»: удаление запрещено навсегда
     * (`D-010`). Идемпотентно — повторный вызов не пишет второе событие.
     */
    @Transactional
    public Chat archiveLocation(UUID actorUserId, UUID gameId, UUID chatId, String reason) {
        requireMaster(actorUserId, gameId);
        Chat chat = requireLocation(gameId, chatId);
        if (chat.archived()) {
            return chat;
        }
        requireText(reason, "archive_reason_required", "Укажите причину архивации", 500);

        chats.markArchived(chatId, reason.strip());
        event(gameId, GameEventType.CHAT_ARCHIVED, actorUserId,
                payload("chatId", chatId.toString()));
        return chats.findById(chatId).orElseThrow();
    }

    /**
     * Установка или снятие картинки локации (`D-106`). `null` — снятие.
     *
     * <p>Ассет обязан принадлежать этой же игре: проверка участия на отдаче пропустила бы
     * чужую картинку, привязанную к своей локации, потому что участие проверяется по игре
     * локации, а не по игре ассета.
     *
     * <p>Прежняя картинка снимается тем же вызовом: `D-077` требует «записать новый,
     * переключить указатель, удалить старый», иначе замещённые файлы занимают диск навсегда.
     * Удаление идёт после переключения и в этой же транзакции — при откате картинка остаётся
     * в прежней ссылке вместе с файлом.
     */
    @Transactional
    public Chat setLocationImage(UUID actorUserId, UUID gameId, UUID chatId, UUID assetId) {
        Game game = requireMaster(actorUserId, gameId);
        requireNotFinished(game, "content_change_after_finish", "После завершения игры мир не меняется");
        Chat chat = requireLocation(gameId, chatId);

        if (assetId != null) {
            Asset asset = assets.findById(assetId)
                    .orElseThrow(() -> ApiException.notFound("asset_not_found", "Ассет не найден"));
            if (!asset.gameId().equals(gameId)) {
                throw ApiException.badRequest(
                        "asset_other_game", "Ассет принадлежит другой игре");
            }
        }

        UUID previous = chat.imageAssetId();
        chats.updateImage(chatId, assetId);

        Map<String, Object> payload = payload("chatId", chatId.toString());
        payload.put("assetId", assetId == null ? null : assetId.toString());
        event(gameId, GameEventType.CHAT_CONTENT_CHANGED, actorUserId, payload);

        if (previous != null && !previous.equals(assetId)) {
            assetService.releaseIfUnreferenced(previous);
        }
        return chats.findById(chatId).orElseThrow();
    }

    /**
     * Отказ на удаление локации. Удаления нет ни до, ни после `READY` (`D-010`, `D-103`),
     * но до `READY` отказ называет замену («архивация»), потому что мастер ждёт подсказки.
     * Право мастера проверяется до отказа: чужой не должен узнать о существовании локации.
     */
    @Transactional(readOnly = true)
    public void rejectLocationDeletion(UUID actorUserId, UUID gameId, UUID chatId) {
        Game game = requireMaster(actorUserId, gameId);
        requireLocation(gameId, chatId);
        throw deletionRefused(game, "локации", "chat_not_deletable");
    }

    /**
     * Создание ориентированного перехода (`REQUIREMENTS §8`). Симметричность мастер задаёт
     * двумя рёбрами: подставить обратное ребро автоматически значило бы решить за него.
     */
    @Transactional
    public Transition createTransition(
            UUID actorUserId, UUID gameId, UUID fromChatId, UUID toChatId, String title) {
        Game game = requireMaster(actorUserId, gameId);
        requireNotFinished(game, "content_change_after_finish", "После завершения игры мир не меняется");
        if (fromChatId == null || toChatId == null) {
            throw ApiException.badRequest("transition_endpoint_required", "Укажите обе локации перехода");
        }
        if (fromChatId.equals(toChatId)) {
            throw ApiException.badRequest("transition_same_location", "Переход не может вести в ту же локацию");
        }
        requireLocation(gameId, fromChatId);
        requireLocation(gameId, toChatId);
        if (title != null && title.strip().length() > Transition.TITLE_MAX) {
            throw ApiException.badRequest(
                    "title_too_long", "Название перехода: до " + Transition.TITLE_MAX + " знаков");
        }

        Instant now = Instant.now();
        Transition transition = new Transition(
                Uuids.v7(), gameId, fromChatId, toChatId, normalize(title), false, null, null, now);
        transitions.insert(transition);

        Map<String, Object> payload = payload("transitionId", transition.id().toString());
        payload.put("fromChatId", fromChatId.toString());
        payload.put("toChatId", toChatId.toString());
        event(gameId, GameEventType.TRANSITION_OPENED, actorUserId, payload);
        return transition;
    }

    /** Переименование перехода. Состояния рёбер не трогает: они про знание, а не про название. */
    @Transactional
    public Transition renameTransition(
            UUID actorUserId, UUID gameId, UUID transitionId, String title) {
        Game game = requireMaster(actorUserId, gameId);
        requireNotFinished(game, "content_change_after_finish", "После завершения игры мир не меняется");
        Transition transition = requireTransition(gameId, transitionId);
        if (title != null && title.strip().length() > Transition.TITLE_MAX) {
            throw ApiException.badRequest(
                    "title_too_long", "Название перехода: до " + Transition.TITLE_MAX + " знаков");
        }
        transitions.updateTitle(transitionId, normalize(title));
        return transitions.findById(transition.id()).orElseThrow();
    }

    /** Архивирование перехода; идемпотентно, как и архивация локации. */
    @Transactional
    public Transition archiveTransition(
            UUID actorUserId, UUID gameId, UUID transitionId, String reason) {
        requireMaster(actorUserId, gameId);
        Transition transition = requireTransition(gameId, transitionId);
        if (transition.archived()) {
            return transition;
        }
        requireText(reason, "archive_reason_required", "Укажите причину архивации", 500);

        transitions.markArchived(transitionId, reason.strip());
        event(gameId, GameEventType.TRANSITION_ARCHIVED, actorUserId,
                payload("transitionId", transitionId.toString()));
        return transitions.findById(transitionId).orElseThrow();
    }

    /** Отказ на удаление перехода; причина отказа та же, что у локации (`D-103`). */
    @Transactional(readOnly = true)
    public void rejectTransitionDeletion(UUID actorUserId, UUID gameId, UUID transitionId) {
        Game game = requireMaster(actorUserId, gameId);
        requireTransition(gameId, transitionId);
        throw deletionRefused(game, "перехода", "transition_not_deletable");
    }

    /**
     * Знание ребра конкретным персонажем (`REQUIREMENTS §8`). Мастер ведёт его по персонажу
     * осознанно: `UNKNOWN → KNOWN_UNAVAILABLE` не открывается сам при входе в локацию, иначе
     * туман войны выдал бы переходы, которых игрок видеть не должен (`D-103`).
     *
     * <p>Причина обязательна для `KNOWN_UNAVAILABLE`: игроку показывается именно текст, и
     * состояние без текста выглядело бы как поломка.
     */
    @Transactional
    public TransitionState setEdgeState(
            UUID actorUserId, UUID gameId, UUID transitionId, UUID characterId,
            EdgeState state, String reason) {
        Game game = requireMaster(actorUserId, gameId);
        requireNotFinished(game, "content_change_after_finish", "После завершения игры мир не меняется");
        requireTransition(gameId, transitionId);
        Character character = characters.findById(characterId)
                .orElseThrow(() -> ApiException.notFound("character_not_found", "Персонаж не найден"));
        if (!character.gameId().equals(gameId)) {
            throw ApiException.badRequest("character_other_game", "Персонаж принадлежит другой игре");
        }
        if (state == null) {
            throw ApiException.badRequest("edge_state_required", "Укажите состояние перехода");
        }
        if (state == EdgeState.KNOWN_UNAVAILABLE) {
            requireText(reason, "edge_reason_required", "Укажите причину недоступности перехода", 500);
        }

        TransitionState stored = edgeStates.upsert(
                transitionId, characterId, state, reason == null ? null : normalize(reason));

        Map<String, Object> payload = payload("transitionId", transitionId.toString());
        payload.put("characterId", characterId.toString());
        payload.put("state", state.name());
        if (stored.reason() != null) {
            payload.put("reason", stored.reason());
        }
        event(gameId, GameEventType.TRANSITION_STATE_CHANGED, actorUserId, payload);
        return stored;
    }

    /**
     * Заведение персонажа. На `E04` сущность нужна минимально — без неё нельзя привязать
     * знание ребра (`REQUIREMENTS §8`); портрет, истории, занятость и скрытность делает `E06`.
     */
    @Transactional
    public Character registerCharacter(UUID actorUserId, UUID gameId, String name) {
        requireMaster(actorUserId, gameId);
        requireText(name, "character_name_required", "Укажите имя персонажа", 200);

        Character character = new Character(Uuids.v7(), gameId, name.strip(), null);
        characters.insert(character);
        return character;
    }

    /**
     * Общая форма отказа на удаление. Удаления нет ни до, ни после `READY` (`D-010`, `D-103`);
     * после старта причина отказа — правила игры, до старта — подсказка про архивацию.
     */
    private static ApiException deletionRefused(Game game, String subjectRu, String code) {
        boolean ready = game.state().readiness() == Readiness.READY;
        String message = ready
                ? "Удаление " + subjectRu + " запрещено после старта игры: используйте архивацию"
                : "Удаление " + subjectRu + " не предусмотрено: используйте архивацию";
        return ApiException.conflict(code, message);
    }

    private Chat requireLocation(UUID gameId, UUID chatId) {
        Chat chat = chats.findById(chatId)
                .orElseThrow(() -> ApiException.notFound("chat_not_found", "Локация не найдена"));
        if (!chat.gameId().equals(gameId)) {
            throw ApiException.notFound("chat_not_found", "Локация не найдена");
        }
        if (chat.kind() != ChatKind.LOCATION) {
            throw ApiException.badRequest("chat_not_location", "Этот чат не является локацией");
        }
        return chat;
    }

    private Transition requireTransition(UUID gameId, UUID transitionId) {
        Transition transition = transitions.findById(transitionId)
                .orElseThrow(() -> ApiException.notFound("transition_not_found", "Переход не найден"));
        if (!transition.gameId().equals(gameId)) {
            throw ApiException.notFound("transition_not_found", "Переход не найден");
        }
        return transition;
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
