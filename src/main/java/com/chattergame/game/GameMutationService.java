package com.chattergame.game;

import com.chattergame.chat.Chat;
import com.chattergame.chat.ChatKind;
import com.chattergame.chat.ChatMember;
import com.chattergame.chat.ChatMemberRepository;
import com.chattergame.chat.ChatMemberState;
import com.chattergame.chat.ChatRepository;
import com.chattergame.chat.EdgeState;
import com.chattergame.chat.Transition;
import com.chattergame.chat.TransitionRepository;
import com.chattergame.chat.TransitionState;
import com.chattergame.chat.TransitionStateRepository;
import com.chattergame.character.Character;
import com.chattergame.character.CharacterField;
import com.chattergame.character.CharacterRepository;
import com.chattergame.character.Presence;
import com.chattergame.character.PresenceRepository;
import com.chattergame.journal.GameEventRepository;
import com.chattergame.journal.GameEventType;
import com.chattergame.journal.Payloads;
import com.chattergame.item.Item;
import com.chattergame.item.ItemPlacement;
import com.chattergame.item.ItemRepository;
import com.chattergame.item.ItemSize;
import com.chattergame.item.ItemType;
import com.chattergame.item.MoveResult;
import com.chattergame.item.PlacementRules;
import com.chattergame.item.Storage;
import com.chattergame.item.StorageKind;
import com.chattergame.item.StorageRepository;
import com.chattergame.media.Asset;
import com.chattergame.media.AssetRepository;
import com.chattergame.message.InboxMaterializer;
import com.chattergame.realtime.RealtimeEvents;
import com.chattergame.message.InboxSubject;
import com.chattergame.message.SubjectKind;
import com.chattergame.message.SystemProjection;
import com.chattergame.message.VisibilityBackfillExecutor;
import com.chattergame.restriction.Restriction;
import com.chattergame.restriction.RestrictionKind;
import com.chattergame.restriction.RestrictionRepository;
import com.chattergame.media.AssetService;
import com.chattergame.turn.Turn;
import com.chattergame.turn.TurnRepository;
import com.chattergame.util.ApiException;
import com.chattergame.util.Uuids;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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

    /** Название шёпота одно на все: у него нет имени, а в списке чатов он помещается участниками. */
    private static final String CHANNEL_WHISPER_TITLE = "Шёпот";

    /** Чат финального обсуждения один на игру (`REQUIREMENTS §7.5`). */
    private static final String CHANNEL_FINAL_TITLE = "Финальное обсуждение";

    /** Личный чат подписан одинаково у обоих: имя собеседника клиент берёт из своего списка. */
    private static final String CHANNEL_MASTER_DM_TITLE = "Мастер";

    private final GameRepository games;
    private final MembershipRepository memberships;
    private final GameEventRepository events;
    private final Payloads payloads;
    private final ReadinessValidation readiness;
    private final ChatRepository chats;
    private final TransitionRepository transitions;
    private final TransitionStateRepository edgeStates;
    private final CharacterRepository characters;
    private final PresenceRepository presence;
    private final StorageRepository storages;
    private final ItemRepository items;
    private final AssetRepository assets;
    private final AssetService assetService;
    private final InboxMaterializer inbox;
    private final RestrictionRepository restrictions;
    private final RealtimeEvents realtime;
    private final TurnRepository turns;
    private final ChatMemberRepository channelMembers;
    private final VisibilityBackfillExecutor visibilityBackfill;

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
            PresenceRepository presence,
            StorageRepository storages,
            ItemRepository items,
            AssetRepository assets,
            AssetService assetService,
            InboxMaterializer inbox,
            RestrictionRepository restrictions,
            RealtimeEvents realtime,
            TurnRepository turns,
            ChatMemberRepository channelMembers,
            VisibilityBackfillExecutor visibilityBackfill) {
        this.games = games;
        this.memberships = memberships;
        this.events = events;
        this.payloads = payloads;
        this.readiness = readiness;
        this.chats = chats;
        this.transitions = transitions;
        this.edgeStates = edgeStates;
        this.characters = characters;
        this.presence = presence;
        this.storages = storages;
        this.items = items;
        this.assets = assets;
        this.assetService = assetService;
        this.inbox = inbox;
        this.restrictions = restrictions;
        this.realtime = realtime;
        this.turns = turns;
        this.channelMembers = channelMembers;
        this.visibilityBackfill = visibilityBackfill;
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

    // --- Пошаговый режим --------------------------------------------------------------

    /**
     * Переключатель пошагового режима (`D-012`). Флаг, а не фаза: включается и выключается
     * посреди партии, и уже записанные сообщения от этого не меняются — ни одна строка
     * `message` и ни одно событие не переписываются, поэтому у отправленного раньше
     * остаётся тот же `budgetConsumed`.
     *
     * <p>Открытый ход переключение не трогает: сгорание несовершённого происходит при
     * явной смене хода, а не при снятии флага (`D-012`). Выключенный флаг просто снимает
     * и блокировку, и расход: ход остаётся в журнале и в таблице как история.
     */
    @Transactional
    public Game setTurnBased(UUID actorUserId, UUID gameId, boolean turnBased) {
        Game game = requireMaster(actorUserId, gameId);
        requireNotFinished(
                game, "turn_based_after_finish", "После завершения игры пошаговый режим не меняется");

        games.updateTurnBased(gameId, turnBased);
        event(gameId, GameEventType.GAME_CONTENT_CHANGED, actorUserId,
                payload("turnBased", turnBased));
        return requireGame(gameId);
    }

    /**
     * Открытие хода (`REQUIREMENTS §14`): мастер открывает ход всем сразу, поэтому
     * открытый ход в игре один. Повторное открытие при открытом ходе отклоняется с
     * машинным кодом, а не «закрывает и открывает следующий»: смена хода — это закрытие,
     * и она обязана быть видна в журнале как отдельное действие мастера.
     */
    @Transactional
    public Turn openTurn(UUID actorUserId, UUID gameId) {
        Game game = requireMaster(actorUserId, gameId);
        requireNotFinished(game, "turn_after_finish", "После завершения игры ход не меняется");
        requireTurnBased(game);

        // Блокировка игры, а не строк хода: у первой смены хода строк ещё нет.
        games.lockById(gameId);
        if (turns.findOpen(gameId).isPresent()) {
            throw ApiException.conflict(
                    "turn_already_open", "Ход уже открыт: сначала закройте текущий");
        }

        long n = turns.maxNumber(gameId) + 1L;
        UUID turnId = Uuids.v7();
        turns.openTurn(turnId, gameId, n);
        event(gameId, GameEventType.TURN_OPENED, actorUserId, payload("n", n));
        // Возвращается прочитанная строка, а не собранная в памяти: момент открытия ставит
        // база, и клиент обязан увидеть её значение, а не своё представление о нём.
        return turns.findLatest(gameId).orElseThrow(() -> new IllegalStateException(
                "Ход не читается сразу после открытия: " + turnId));
    }

    /**
     * Закрытие хода. Несовершённые действия сгорают здесь: строки бюджета остаются
     * привязанными к закрытому ходу, поэтому следующий ход начинается с чистых флагов
     * и прошлое не «дотягивается» до игрока через ход (`D-012`).
     */
    @Transactional
    public Turn closeTurn(UUID actorUserId, UUID gameId) {
        Game game = requireMaster(actorUserId, gameId);
        requireNotFinished(game, "turn_after_finish", "После завершения игры ход не меняется");

        games.lockById(gameId);
        Turn open = turns.findOpen(gameId)
                .orElseThrow(() -> ApiException.conflict("turn_not_open", "Открытого хода нет"));
        turns.close(open.id(), actorUserId);
        event(gameId, GameEventType.TURN_CLOSED, actorUserId, payload("n", open.n()));
        // Момент закрытия и автор берутся из строки: часы ставит база, а не приложение.
        return turns.findLatest(gameId).orElseThrow(() -> new IllegalStateException(
                "Ход не читается сразу после закрытия: " + open.id()));
    }

    private static void requireTurnBased(Game game) {
        if (!game.turnBased()) {
            throw ApiException.conflict(
                    "turn_based_off", "Пошаговый режим выключен: ход открывать нечего");
        }
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
        // Чат финального обсуждения открывается завершением и только им (`REQUIREMENTS §7.5`).
        // Отдельного события у него нет: он часть перехода, а не вторая мутация, и журнал
        // обязан воспроизвести его из `GAME_FINISHED`, а не из соседней строки (`D-033`).
        Chat finalChat = transition == GameTransition.FINISH ? openFinalChat(game) : null;
        games.updateState(next);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("from", game.state().readiness().name() + "/" + game.state().phase().name());
        payload.put("to", next.readiness().name() + "/" + next.phase().name());
        if (next.pauseReason() != null) {
            payload.put("reason", next.pauseReason());
        }
        if (finalChat != null) {
            payload.put("finalChatId", finalChat.id().toString());
            payload.put("finalChatTitle", finalChat.title());
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
                status == MembershipStatus.LEFT ? now : null,
                null,
                null);
        memberships.insert(membership);
        event(gameId, eventTypeOf(status), actorUserId, payload("userId", userId.toString()));
        // Личный чат с мастером заводится входом в игру, а не отдельным действием (`D-037`).
        openMasterDm(requireGame(gameId), membership);
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
        Membership next = current.withStatus(status, now);
        memberships.updateStatus(next);
        event(gameId, eventTypeOf(status), actorUserId, membershipPayload(next, current.reservedCharacterId()));
        if (status == MembershipStatus.JOINED) {
            openMasterDm(requireGame(gameId), next);
        }
        return next;
    }

    private static GameEventType eventTypeOf(MembershipStatus status) {
        return switch (status) {
            case INVITED -> GameEventType.MEMBERSHIP_INVITED;
            case JOINED -> GameEventType.MEMBERSHIP_JOINED;
            case DECLINED -> GameEventType.MEMBERSHIP_DECLINED;
            case LEFT -> GameEventType.MEMBERSHIP_LEFT;
            case BANNED -> GameEventType.MEMBERSHIP_BANNED;
        };
    }

    // --- Приглашения, вход и выход (E13) ------------------------------------------------

    /**
     * Приглашение мастером (`REQUIREMENTS §6`, `PLAN` E13). Приглашает только мастер
     * (`D-131`); игрок входа не открывает никому.
     *
     * <p>Имя цели приходит сюда уже разрешённым в `userId`: поиск пользователя по логину —
     * забота `MembershipService`, а не агрегата игры, потому что иначе `game` зависел бы от
     * учётных записей. Несуществующий логин отвергается там же, до всякой записи: строка
     * участия на выдуманного человека завела бы призрак в составе игры.
     *
     * <p>Персонаж <b>бронируется</b>, а не занимается (`D-130`): `D-100` прямо говорит, что
     * `INVITED` в захвате слота не участвует. Бронь живёт в `membership.reserved_character_id`
     * и снимается отказом, баном или выходом; поэтому приглашённый не мешает мастеру
     * распоряжаться слотом, но и другой игрок не может занять обещанное.
     */
    @Transactional
    public Membership invitePlayer(
            UUID actorUserId, UUID gameId, UUID userId, MembershipRole role, UUID characterId) {
        Game game = requireMaster(actorUserId, gameId);
        requireNotFinished(game, "game_finished", "Игра завершена: состав участников не меняется");
        if (role == MembershipRole.MASTER) {
            throw ApiException.badRequest(
                    "master_membership_immutable", "Мастерство не приглашается и не передаётся");
        }
        if (role == MembershipRole.OBSERVER && characterId != null) {
            throw ApiException.badRequest(
                    "observer_cannot_reserve", "Наблюдателю персонаж не бронируется");
        }

        Membership existing = memberships.find(gameId, userId).orElse(null);
        if (existing != null && existing.role() == MembershipRole.MASTER) {
            throw ApiException.conflict(
                    "master_membership_immutable", "Мастер уже участвует в этой игре");
        }
        if (existing != null && existing.banned()) {
            throw ApiException.conflict(
                    "membership_banned", "Участие запрещено мастером: сначала снимите бан");
        }
        if (existing != null && existing.invited()
                && Objects.equals(existing.reservedCharacterId(), characterId)) {
            return existing;
        }
        UUID reserved = characterId == null ? null : reserveCharacter(gameId, characterId);
        Instant now = Instant.now();
        Membership membership;
        if (existing == null) {
            membership = new Membership(
                    Uuids.v7(),
                    gameId,
                    userId,
                    role,
                    MembershipStatus.INVITED,
                    now,
                    null,
                    null,
                    reserved,
                    null);
            memberships.insert(membership);
        } else {
            membership = existing
                    .withRole(role)
                    .withStatus(MembershipStatus.INVITED, now)
                    .withReservedCharacter(reserved);
            memberships.updateStatus(membership);
        }
        Map<String, Object> payload = membershipPayload(membership, membership.userId());
        payload.put("midGame", game.state().phase() == Phase.RUNNING);
        event(gameId, GameEventType.MEMBERSHIP_INVITED, actorUserId, payload);
        return membership;
    }

    /**
     * Согласие приглашённого: `INVITED → JOINED` (`PLAN` E13). Захват слота происходит
     * только здесь (`D-130`) и под блокировкой строки персонажа (`D-100`): двое согласий
     * на один слот не должны оба стать «занято». Отказ второго — спорная ситуация, и она
     * разрешается базой, а не порядком проверок в сервисе.
     */
    @Transactional
    public Membership acceptInvitation(UUID userId, UUID gameId) {
        Game game = requireGameForMember(userId, gameId);
        Membership membership = requireInvited(gameId, userId);
        // Приглашение наблюдателя слота не бронирует (`D-130`), и занимать ему нечего:
        // согласие такого участия — только смена статуса.
        if (membership.reservedCharacterId() != null) {
            claimCharacter(gameId, membership, membership.reservedCharacterId(), false);
        }
        Membership joined = membership.withStatus(MembershipStatus.JOINED, Instant.now());
        memberships.updateStatus(joined);
        event(gameId, GameEventType.MEMBERSHIP_JOINED, userId, membershipPayload(joined, joined.userId()));
        if (joined.role() == MembershipRole.PLAYER) {
            openMasterDm(game, joined);
        }
        scheduleBackfill(joined, game);
        return joined;
    }

    /**
     * Отказ от приглашения: `INVITED → DECLINED` (`D-123`). Бронь снимается — иначе
     * отказавшийся держал бы персонажа занятым навсегда, а сам в игру не собирался.
     */
    @Transactional
    public Membership declineInvitation(UUID userId, UUID gameId) {
        requireGameForMember(userId, gameId);
        Membership membership = requireInvited(gameId, userId);
        UUID reserved = membership.reservedCharacterId();
        Membership declined = membership.withStatus(MembershipStatus.DECLINED, Instant.now());
        memberships.updateStatus(declined);
        Map<String, Object> payload = membershipPayload(declined, userId);
        if (reserved != null) {
            payload.put("characterId", reserved.toString());
        }
        event(gameId, GameEventType.MEMBERSHIP_DECLINED, userId, payload);
        return declined;
    }

    /**
     * Свободный вход в публичную игру (`REQUIREMENTS §4`, `PLAN` E13). Открыт после `READY`,
     * включая середину `RUNNING`; в приватную игру так не войти.
     *
     * <p>Роль выбирается по наличию слота (`D-132`): есть свободный играбельный персонаж —
     * вход игроком за него, нет — вход наблюдателем. Иначе человек либо входил бы в роль,
     * которую нечем занять, либо получал отказ там, где участием можно распорядиться.
     * Повторный вход идемпотентен, а наблюдатель поднимается до игрока, когда слот освободится.
     */
    @Transactional
    public Membership joinOpenGame(UUID userId, UUID gameId) {
        Game game = requireGame(gameId);
        requireNotFinished(game, "game_finished", "Игра завершена: вход закрыт");
        if (game.state().visibility() != Visibility.PUBLIC) {
            throw ApiException.forbidden(
                    "private_game", "Это приватная игра: вход только по приглашению мастера");
        }
        if (game.state().readiness() != Readiness.READY) {
            throw ApiException.conflict(
                    "game_not_ready", "Игра ещё не готова: вход откроется после READY");
        }
        Membership existing = memberships.find(gameId, userId).orElse(null);
        if (existing != null && existing.role() == MembershipRole.MASTER) {
            return existing;
        }
        if (existing != null && existing.banned()) {
            throw ApiException.forbidden(
                    "membership_banned", "Участие в этой игре запрещено мастером");
        }

        UUID slot = freeCharacterId(gameId);
        MembershipRole role = slot == null ? MembershipRole.OBSERVER : MembershipRole.PLAYER;
        if (existing != null && existing.joined()
                && existing.role() == MembershipRole.PLAYER && slot != null) {
            // Игрок уже вошёл и слот свободен: персонаж мог освободиться после его входа.
            claimCharacter(gameId, existing, slot, false);
            return existing;
        }
        if (existing != null && existing.joined() && existing.role() == role) {
            return existing;
        }

        Instant now = Instant.now();
        Membership membership;
        if (existing == null) {
            membership = new Membership(
                    Uuids.v7(), gameId, userId, role, MembershipStatus.JOINED, now, now, null, null, null);
            memberships.insert(membership);
        } else {
            membership = existing.withRole(role).withStatus(MembershipStatus.JOINED, now);
            memberships.updateStatus(membership);
        }
        event(gameId, GameEventType.MEMBERSHIP_JOINED, userId, membershipPayload(membership, userId));
        if (role == MembershipRole.PLAYER) {
            claimCharacter(gameId, membership, slot, false);
            openMasterDm(game, membership);
        }
        scheduleBackfill(membership, game);
        return membership;
    }

    /**
     * Добровольный выход (`PLAN` E13, задача 5): `JOINED → LEFT`. История персонажей
     * сохраняется целиком — сообщения, присутствие в прошлом и карточки остаются читаемыми
     * (`D-009`); снимается только занятость и текущее место, иначе персонаж остался бы
     * «ничьим, но стоящим» и лента показывала бы его всем.
     */
    @Transactional
    public Membership leaveGame(UUID userId, UUID gameId) {
        requireGameForMember(userId, gameId);
        Membership membership = requireJoined(gameId, userId, "not_joined", "Вы не участвуете в игре");
        if (membership.role() == MembershipRole.MASTER) {
            throw ApiException.conflict(
                    "master_cannot_leave", "Мастер не выходит из игры: он остаётся ответственным за неё");
        }
        releaseCharacters(gameId, membership);
        Membership left = membership.withStatus(MembershipStatus.LEFT, Instant.now());
        memberships.updateStatus(left);
        event(gameId, GameEventType.MEMBERSHIP_LEFT, userId, membershipPayload(left, userId));
        return left;
    }

    /**
     * Бан в одной игре (`REQUIREMENTS §6`): статус участия, а не глобальный запрет, поэтому
     * другие игры человека он не трогает (`D-133`). Слот освобождается так же, как при
     * выходе: забаненный не продолжает играть своим персонажем.
     */
    @Transactional
    public Membership banPlayer(UUID actorUserId, UUID gameId, UUID userId) {
        requireMaster(actorUserId, gameId);
        Membership membership = requireJoined(gameId, userId, "membership_not_found", "Участие не найдено");
        if (membership.role() == MembershipRole.MASTER) {
            throw ApiException.conflict(
                    "master_membership_immutable", "Статус участия мастера не меняется");
        }
        releaseCharacters(gameId, membership);
        Membership banned = membership.withStatus(MembershipStatus.BANNED, Instant.now());
        memberships.updateStatus(banned);
        event(gameId, GameEventType.MEMBERSHIP_BANNED, actorUserId, membershipPayload(banned, userId));
        return banned;
    }

    /**
     * Снятие бана: `BANNED → LEFT`. Именно `LEFT`, а не `JOINED`: снятие запрета возвращает
     * человеку право входа, но не вводит его в игру за него (`D-133`). Дальше он входит сам
     * в публичной игре или ждёт приглашения в приватной.
     */
    @Transactional
    public Membership unbanPlayer(UUID actorUserId, UUID gameId, UUID userId) {
        requireMaster(actorUserId, gameId);
        Membership membership = memberships.find(gameId, userId)
                .orElseThrow(() -> ApiException.notFound("membership_not_found", "Участие не найдено"));
        if (!membership.banned()) {
            throw ApiException.conflict("not_banned", "Этот игрок в игре не забанен");
        }
        Membership unbanned = membership.withStatus(MembershipStatus.LEFT, Instant.now());
        memberships.updateStatus(unbanned);
        event(gameId, GameEventType.MEMBERSHIP_UNBANNED, actorUserId, membershipPayload(unbanned, userId));
        return unbanned;
    }

    /**
     * Занятие свободного персонажа (`PLAN` E13, задача 2). Блокировка строки персонажа —
     * то самое место, где «один игрок на слот» становится правдой (`D-100`): без неё двое
     * прочитали бы «свободен» и оба записали себя. Ограничения «один персонаж на игрока»
     * нет и не должно быть (`CONVENTIONS`): вторым персонажем играют так же, как первым.
     */
    @Transactional
    public Character claimCharacter(UUID userId, UUID gameId, UUID characterId) {
        return claimCharacter(userId, gameId, characterId, null);
    }

    /**
     * Занятие персонажа вместе с «пожеланиями по отыгрышу» (`REQUIREMENTS §17`). Пожелание —
     * свободный текст игрока, который мастер читает при распределении ролей; оно не канон,
     * поэтому живёт на участии (`D-139`), а не на карточке, и пишется той же транзакцией,
     * что и занятие: раздельные вызовы оставляли бы слот занятым без пожелания при обрыве.
     *
     * <p>Пустая строка и `null` значат разное: `null` — «не указал вовсе», пустая — «стёр
     * написанное». Оба состояния законны, поэтому заполнение идёт отдельным `UPDATE`
     * колонки, а не перезаписью строки участия целиком: иначе запись пожелания затирала бы
     * решение мастера о бане или выходе, принятое между чтением и записью.
     */
    @Transactional
    public Character claimCharacter(UUID userId, UUID gameId, UUID characterId, String roleplayWishes) {
        Game game = requireGameForMember(userId, gameId);
        requireNotFinished(game, "game_finished", "Игра завершена: персонажа не занять");
        Membership membership = requireJoined(gameId, userId, "not_joined", "Сначала войдите в игру");
        if (membership.role() == MembershipRole.OBSERVER) {
            throw ApiException.forbidden(
                    "observer_cannot_occupy", "Наблюдатель не занимает персонажа: он читает игру");
        }
        if (roleplayWishes != null) {
            memberships.updateRoleplayWishes(membership.id(), requireWishes(roleplayWishes));
        }
        return claimCharacter(gameId, membership, characterId, true);
    }

    /** Предел текста пожеланий проверяется на сервисе ради машинного кода, не только базой. */
    private static String requireWishes(String wishes) {
        String normalized = wishes.strip();
        if (normalized.length() > Membership.ROLEPLAY_WISHES_MAX) {
            throw ApiException.badRequest(
                    "wishes_too_long",
                    "Пожелания по отыгрышу: до " + Membership.ROLEPLAY_WISHES_MAX + " знаков");
        }
        return normalized.isEmpty() ? null : normalized;
    }

    /**
     * Освобождение персонажа: слот становится свободным и может достаться другому
     * (`D-100`). Присутствие закрывается: «освобождён, но стоит в комнате» — это персонаж,
     * которым никто не играет, и показывать его другим было бы неправдой.
     */
    @Transactional
    public Character releaseCharacter(UUID userId, UUID gameId, UUID characterId) {
        requireGameForMember(userId, gameId);
        Membership membership = requireJoined(gameId, userId, "not_joined", "Сначала войдите в игру");
        Character character = requireCharacter(gameId, characterId);
        if (!Objects.equals(character.occupantMembershipId(), membership.id())) {
            throw ApiException.forbidden("not_your_character", "Этот персонаж занят не вами");
        }
        releaseCharacter(gameId, membership, character);
        return requireCharacter(gameId, characterId);
    }

    private UUID reserveCharacter(UUID gameId, UUID characterId) {
        Character character = requireCharacter(gameId, characterId);
        if (character.archived()) {
            throw ApiException.conflict("character_archived", "Персонаж выведен из игры");
        }
        if (!character.playable() || character.npc()) {
            throw ApiException.badRequest(
                    "character_not_playable", "Этот персонаж не играбельный: его нельзя занять");
        }
        if (character.occupantMembershipId() != null) {
            throw ApiException.conflict("character_occupied", "Персонаж уже занят другим игроком");
        }
        memberships.findReserving(characterId).ifPresent(reserving -> {
            throw ApiException.conflict(
                    "character_reserved", "Персонаж забронирован другим приглашением");
        });
        return characterId;
    }

    /** Захват слота под блокировкой строки: бронь, согласие и свободное занятие — один путь. */
    private Character claimCharacter(
            UUID gameId, Membership membership, UUID characterId, boolean explicit) {
        if (characterId == null) {
            throw ApiException.badRequest(
                    "character_required", "Укажите персонажа, которого занимаете");
        }
        Character locked = characters.lockById(characterId)
                .orElseThrow(() -> ApiException.notFound("character_not_found", "Персонаж не найден"));
        if (!locked.gameId().equals(gameId)) {
            throw ApiException.notFound("character_not_found", "Персонаж не найден");
        }
        if (locked.archived()) {
            throw ApiException.conflict("character_archived", "Персонаж выведен из игры");
        }
        if (!locked.playable() || locked.npc()) {
            throw ApiException.badRequest(
                    "character_not_playable", "Этот персонаж не играбельный: его нельзя занять");
        }
        if (locked.occupantMembershipId() != null
                && !locked.occupantMembershipId().equals(membership.id())) {
            throw ApiException.conflict("character_occupied", "Персонаж уже занят другим игроком");
        }
        if (!explicit && locked.occupantMembershipId() == null) {
            memberships.findReserving(characterId)
                    .filter(reserving -> !reserving.id().equals(membership.id()))
                    .ifPresent(reserving -> {
                        throw ApiException.conflict(
                                "character_reserved", "Персонаж забронирован другим приглашением");
                    });
        }
        if (locked.occupantMembershipId() == null) {
            characters.setOccupant(characterId, membership.id());
        }
        Map<String, Object> payload = payload("characterId", characterId.toString());
        payload.put("membershipId", membership.id().toString());
        event(gameId, GameEventType.CHARACTER_OCCUPIED, membership.userId(), payload);
        return locked;
    }

    /** Снятие занятости: присутствие закрывается, участие в закрытых каналах — тоже. */
    private void releaseCharacter(UUID gameId, Membership membership, Character character) {
        characters.setOccupant(character.id(), null);
        presence.findActive(character.id()).ifPresent(active -> presence.close(active.id()));
        for (ChatMember member : channelMembers.findActiveBySubject(
                gameId, InboxSubject.character(character.id()))) {
            channelMembers.close(member.id(), ChatMemberState.LEFT);
        }
        Map<String, Object> payload = payload("characterId", character.id().toString());
        payload.put("membershipId", membership.id().toString());
        event(gameId, GameEventType.CHARACTER_RELEASED, membership.userId(), payload);
    }

    /** Освобождение всех слотов участия: выход и бан снимают занятость целиком. */
    private void releaseCharacters(UUID gameId, Membership membership) {
        for (Character character : characters.findByOccupant(membership.id())) {
            releaseCharacter(gameId, membership, character);
        }
    }

    /** Первый свободный играбельный персонаж: порядок по имени, чтобы выбор был воспроизводим. */
    private UUID freeCharacterId(UUID gameId) {
        return characters.findByGame(gameId).stream()
                .filter(character -> !character.archived()
                        && character.playable()
                        && !character.npc()
                        && character.occupantMembershipId() == null)
                .filter(character -> memberships.findReserving(character.id()).isEmpty())
                .map(Character::id)
                .sorted()
                .findFirst()
                .orElse(null);
    }

    /**
     * Досчёт видимости при входе в середину игры (`D-086`). Признак ставится в той же
     * транзакции, что и вход: если узел упадёт до досчёта, следующий прогон добрать не
     * забудет. Сам досчёт идёт вне запроса — он пишет тысячи строк, и ждать его игроку
     * незачем.
     */
    private void scheduleBackfill(Membership membership, Game game) {
        if (game.state().phase() != Phase.RUNNING) {
            return;
        }
        memberships.setBackfillPending(membership.id(), true);
        visibilityBackfill.schedule();
    }

    private Membership requireInvited(UUID gameId, UUID userId) {
        Membership membership = memberships.find(gameId, userId)
                .orElseThrow(() -> ApiException.forbidden("not_a_member", "Нет доступа к этой игре"));
        if (!membership.invited()) {
            throw ApiException.conflict("not_invited", "Приглашения в эту игру нет");
        }
        return membership;
    }

    private Membership requireJoined(UUID gameId, UUID userId, String code, String message) {
        Membership membership = memberships.find(gameId, userId)
                .orElseThrow(() -> ApiException.forbidden("not_a_member", "Нет доступа к этой игре"));
        if (!membership.joined()) {
            throw ApiException.conflict(code, message);
        }
        return membership;
    }

    private Game requireGameForMember(UUID userId, UUID gameId) {
        Game game = requireGame(gameId);
        Membership membership = memberships.find(gameId, userId)
                .orElseThrow(() -> ApiException.forbidden("not_a_member", "Нет доступа к этой игре"));
        if (membership.banned()) {
            throw ApiException.forbidden("membership_banned", "Участие в этой игре запрещено мастером");
        }
        return game;
    }

    /**
     * Payload события участия. `role` и `status` рядом с `userId`, потому что читатель
     * журнала (аудит, экспорт `O02`) не должен ходить за ними в `membership`: строка могла
     * смениться после события, а событие обязано читаться как было.
     */
    private static Map<String, Object> membershipPayload(Membership membership, UUID userId) {
        Map<String, Object> payload = payload("userId", userId.toString());
        payload.put("role", membership.role().name());
        payload.put("status", membership.status().name());
        if (membership.reservedCharacterId() != null) {
            payload.put("characterId", membership.reservedCharacterId().toString());
        }
        return payload;
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
                false,
                now);
        chats.insert(chat);
        Map<String, Object> payload = payload("chatId", chat.id().toString());
        payload.put("kind", chat.kind().name());
        payload.put("title", chat.title());
        eventInChat(gameId, GameEventType.CHAT_CREATED, actorUserId, chat.id(), null, payload);
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
        eventInChat(gameId, GameEventType.CHAT_CONTENT_CHANGED, actorUserId, chatId, null,
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
        eventInChat(gameId, GameEventType.CHAT_ARCHIVED, actorUserId, chatId, null,
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
        eventInChat(gameId, GameEventType.TRANSITION_OPENED, actorUserId, fromChatId, null, payload);
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
        eventInChat(gameId, GameEventType.TRANSITION_ARCHIVED, actorUserId, transition.fromChatId(), null,
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
        eventInChat(gameId, GameEventType.TRANSITION_STATE_CHANGED, actorUserId,
                requireTransition(gameId, transitionId).fromChatId(), characterId, payload);
        return stored;
    }

    // --- Персонажи (E06) ---------------------------------------------------------------

    /**
     * Заведение персонажа мастером (`REQUIREMENTS §9`, `D-020`). Карточка заполняется целиком
     * одним вызовом: `createCharacter` без остальных полей — это персонаж, которого мастер
     * завёл «на будущее», а событие `CHARACTER_CREATED` после этого соврало бы о содержимом.
     */
    @Transactional
    public Character createCharacter(
            UUID actorUserId,
            UUID gameId,
            String name,
            UUID portraitAssetId,
            String appearance,
            String publicHistory,
            String privateHistory,
            boolean playable,
            boolean npc) {
        Game game = requireMaster(actorUserId, gameId);
        requireNotFinished(game, "content_change_after_finish", "После завершения игры мир не меняется");
        requireText(name, "character_name_required", "Укажите имя персонажа", Character.NAME_MAX);
        requirePortraitOfGame(gameId, portraitAssetId);

        Character character = new Character(
                Uuids.v7(),
                gameId,
                name.strip(),
                portraitAssetId,
                normalize(appearance),
                normalize(publicHistory),
                normalize(privateHistory),
                playable,
                npc,
                null,
                false,
                null);
        characters.insert(character);
        event(gameId, GameEventType.CHARACTER_CREATED, actorUserId,
                payload("characterId", character.id().toString()));
        return character;
    }

    /**
     * Правка карточки персонажа. Отдельный тип события от архивации: игроку показывается
     * либо изменившийся персонаж, либо выведенный из игры, и одним событием эти случаи
     * различить нельзя (`D-009`).
     */
    @Transactional
    public Character updateCharacterCard(
            UUID actorUserId,
            UUID gameId,
            UUID characterId,
            String name,
            UUID portraitAssetId,
            String appearance,
            String publicHistory,
            String privateHistory,
            boolean playable,
            boolean npc) {
        Game game = requireMaster(actorUserId, gameId);
        requireNotFinished(game, "content_change_after_finish", "После завершения игры мир не меняется");
        // Карточка пишется целиком, поэтому строка берётся под `FOR UPDATE`, а не обычным
        // чтением: иначе правка карточки и одобрение заявки на то же поле прочли бы одну
        // версию строки и вторая запись затёрла бы первую (`D-034`).
        Character character = characters.lockById(characterId)
                .orElseThrow(() -> ApiException.notFound("character_not_found", "Персонаж не найден"));
        if (!character.gameId().equals(gameId)) {
            throw ApiException.notFound("character_not_found", "Персонаж не найден");
        }
        requireText(name, "character_name_required", "Укажите имя персонажа", Character.NAME_MAX);
        requirePortraitOfGame(gameId, portraitAssetId);

        Character updated = new Character(
                character.id(),
                gameId,
                name.strip(),
                portraitAssetId,
                normalize(appearance),
                normalize(publicHistory),
                normalize(privateHistory),
                playable,
                npc,
                character.occupantMembershipId(),
                character.archived(),
                character.archivedReason());
        characters.updateCard(updated);
        event(gameId, GameEventType.CHARACTER_CONTENT_CHANGED, actorUserId,
                payload("characterId", characterId.toString()));
        return requireCharacter(gameId, characterId);
    }

    /**
     * Архивирование персонажа — единственный способ убрать его из игры (`D-009`, `D-010`).
     * Активное присутствие закрывается: архивированный персонаж не стоит ни в одной локации,
     * иначе он остался бы в списках присутствующих живой игры, что и запрещает критерий
     * приёмки `E06`. Строки `presence` при этом не удаляются — история остаётся (`D-008`).
     *
     * <p>Идемпотентно: повторный вызов не пишет второе событие и не меняет первую причину.
     */
    @Transactional
    public Character archiveCharacter(
            UUID actorUserId, UUID gameId, UUID characterId, String reason) {
        Game game = requireMaster(actorUserId, gameId);
        Character character = requireCharacter(gameId, characterId);
        if (character.archived()) {
            return character;
        }
        requireText(reason, "archive_reason_required", "Укажите причину архивации", 500);

        presence.findActive(characterId).ifPresent(active -> presence.close(active.id()));
        characters.markArchived(characterId, reason.strip());
        event(gameId, GameEventType.CHARACTER_ARCHIVED, actorUserId,
                payload("characterId", characterId.toString()));
        return requireCharacter(gameId, characterId);
    }

    /**
     * Право на смену скрытности (`REQUIREMENTS §9`, `D-006`, `D-108`). Вход в скрытность —
     * жест мастера по действию персонажа, и раньше `E13` его некому подтвердить; выход же
     * свободен и владельцу слота. Разделение сохраняется и после появления занятости: игрок
     * сам возвращает персонажа в игру, но спрятать его без мастера не может.
     */
    private Game requireStealthAuthority(
            UUID actorUserId, UUID gameId, UUID characterId, boolean hidingIn) {
        Game game = requireGame(gameId);
        Membership membership = memberships.find(gameId, actorUserId)
                .orElseThrow(() -> ApiException.forbidden("not_master", "Изменять мир может только мастер"));
        if (membership.role() == MembershipRole.MASTER) {
            return game;
        }
        if (hidingIn) {
            throw ApiException.forbidden(
                    "stealth_requires_master", "В скрытность персонажа вводит мастер");
        }
        Character character = requireCharacter(gameId, characterId);
        if (membership.joined() && Objects.equals(character.occupantMembershipId(), membership.id())) {
            return game;
        }
        throw ApiException.forbidden("not_your_character", "Это чужой персонаж");
    }

    /** Отказ на удаление персонажа; причина отказа та же, что у локации (`D-010`, `D-103`). */
    @Transactional(readOnly = true)
    public void rejectCharacterDeletion(UUID actorUserId, UUID gameId, UUID characterId) {
        Game game = requireMaster(actorUserId, gameId);
        requireCharacter(gameId, characterId);
        throw deletionRefused(game, "персонажа", "character_not_deletable");
    }

    /**
     * Размещение и перемещение персонажа (`REQUIREMENTS §9`: стартовое размещение задаёт
     * мастер). Мастер перемещает всегда и без проверки перехода: «перемещение только по
     * `AVAILABLE` переходу» адресовано игроку (`REQUIREMENTS §8`), а право игрока идти
     * своим персонажем появляется вместе с занятостью, то есть на `E13` (`D-107`). Проверять
     * здесь переход было бы нечего: у вызвавшего всегда есть право мастера.
     *
     * <p>Уход и вход — две записи одной транзакции: присутствие не переписывается, прежняя
     * строка закрывается (`D-007`). События два, потому что для читающего ленты это разные
     * сообщения: «ушёл» и «появился».
     */
    @Transactional
    public Presence placeCharacter(
            UUID actorUserId, UUID gameId, UUID characterId, UUID chatId) {
        Game game = requireMaster(actorUserId, gameId);
        return placeCharacterInternal(game, actorUserId, characterId, chatId);
    }

    /**
     * Перемещение персонажа игроком по переходу (`REQUIREMENTS §8`, `D-107`). Мастер двигает
     * кого угодно куда угодно; игрок идёт только своим персонажем и только по переходу,
     * который для этого персонажа открыт (`AVAILABLE`). Проверка состояния ребра живёт
     * здесь, а не в контроллере: то же правило придёт из заявки игрока (`E14`).
     *
     * <p>Направление берётся у ребра, а не у клиента: иначе игрок указал бы «откуда», и
     * перемещение из локации, где персонажа нет, прошло бы проверку перехода.
     */
    @Transactional
    public Presence moveCharacter(
            UUID userId, UUID gameId, UUID characterId, UUID transitionId) {
        Game game = requireGameForMember(userId, gameId);
        requireNotFinished(game, "content_change_after_finish", "После завершения игры мир не меняется");
        Membership membership = requireJoined(gameId, userId, "not_joined", "Сначала войдите в игру");
        Character character = requireCharacter(gameId, characterId);
        if (!Objects.equals(character.occupantMembershipId(), membership.id())) {
            throw ApiException.forbidden("not_your_character", "Этот персонаж занят не вами");
        }
        Transition transition = transitions.findById(transitionId)
                .orElseThrow(() -> ApiException.notFound("transition_not_found", "Переход не найден"));
        if (!transition.gameId().equals(gameId)) {
            throw ApiException.notFound("transition_not_found", "Переход не найден");
        }
        if (transition.archived()) {
            throw ApiException.conflict("transition_archived", "Переход выведен из игры");
        }
        Presence current = presence.findActive(characterId)
                .orElseThrow(() -> ApiException.conflict(
                        "character_not_placed", "Персонаж нигде не находится: идти неоткуда"));
        if (!current.chatId().equals(transition.fromChatId())) {
            throw ApiException.conflict("not_at_transition", "Переход начинается не там, где вы стоите");
        }
        EdgeState edge = edgeStates.find(transitionId, characterId).map(TransitionState::state)
                .orElse(EdgeState.UNKNOWN);
        if (edge != EdgeState.AVAILABLE) {
            throw ApiException.forbidden(
                    "transition_unavailable", "Этот переход для вашего персонажа закрыт");
        }
        return placeCharacterInternal(game, userId, characterId, transition.toChatId());
    }

    private Presence placeCharacterInternal(
            Game game, UUID actorUserId, UUID characterId, UUID chatId) {
        return placeCharacterInternal(game, actorUserId, characterId, chatId, Map.of());
    }

    /**
     * Та же постановка, но с добавочными полями в payload обоих событий (`E14`): откату
     * заявки нужна прежняя локация, а взять её можно только из журнала — восстанавливать
     * состояние по таблице значило бы восстанавливать по тому, что откат и меняет (`D-033`).
     */
    private Presence placeCharacterInternal(
            Game game,
            UUID actorUserId,
            UUID characterId,
            UUID chatId,
            Map<String, Object> extras) {
        requireNotFinished(game, "content_change_after_finish", "После завершения игры мир не меняется");
        Character character = requireCharacter(game.id(), characterId);
        if (character.archived()) {
            throw ApiException.conflict(
                    "character_archived", "Персонаж выведен из игры: сначала снимите архивацию");
        }
        Chat location = requireLocation(game.id(), chatId);
        if (location.archived()) {
            throw ApiException.conflict(
                    "chat_archived", "Локация выведена из игры: перемещение в неё запрещено");
        }

        Presence current = presence.findActive(characterId).orElse(null);
        if (current != null && current.chatId().equals(location.id())) {
            return current;
        }
        if (current != null) {
            presence.close(current.id());
            eventInChat(game.id(), GameEventType.CHARACTER_LEFT, actorUserId, current.chatId(), characterId,
                    withExtras(placementPayload(characterId, current), extras));
        }
        // Скрытность переезжает вместе с персонажем: иначе перемещение скрытого персонажа
        // молча показало бы его всем в новой локации (`D-007`).
        boolean hidden = current != null && current.hidden();
        Presence placed = new Presence(Uuids.v7(), characterId, location.id(), hidden, Instant.now(), null);
        presence.insert(placed);
        eventInChat(game.id(), GameEventType.CHARACTER_ENTERED, actorUserId, placed.chatId(), characterId,
                withExtras(placementPayload(characterId, placed), extras));
        return placed;
    }

    private static Map<String, Object> withExtras(
            Map<String, Object> payload, Map<String, Object> extras) {
        if (extras.isEmpty()) {
            return payload;
        }
        Map<String, Object> merged = new LinkedHashMap<>(payload);
        merged.putAll(extras);
        return merged;
    }

    /**
     * Вход в скрытность и выход из неё (`D-006`). Вход ведёт мастер, реагируя на действие
     * персонажа: игрок описывает уход в тень, мастер его подтверждает (`D-108`). Выход — та же
     * мутация с обратным значением; «выход самим персонажем» — право владельца слота, поэтому
     * он приходит вместе с занятостью, потому что «свой персонаж» на `E06` ещё не определён
     * (`D-107`, `D-108`).
     *
     * <p>Уход публично видимого персонажа оставляет ещё одно событие —
     * `CHARACTER_APPEARED_LEFT`: для остальных он «отобразился как ушедший» (`D-007`), а не
     * пропал из ответа молча. Для скрытого с самого начала такого события нет: посторонние
     * его не видели, показывать им нечего.
     *
     * <p>Идемпотентно: повторное скрытие не пишет второе событие, иначе журнал показывал бы
     * уход там, где ничего не менялось.
     */
    @Transactional
    public Presence setCharacterStealth(
            UUID actorUserId, UUID gameId, UUID characterId, boolean hidden, String reason) {
        Game game = requireStealthAuthority(actorUserId, gameId, characterId, hidden);
        requireNotFinished(game, "content_change_after_finish", "После завершения игры мир не меняется");
        requireCharacter(gameId, characterId);
        Presence current = presence.findActive(characterId)
                .orElseThrow(() -> ApiException.conflict(
                        "character_not_placed", "Персонаж нигде не находится: скрытность не к чему применить"));
        if (current.hidden() == hidden) {
            return current;
        }

        presence.setHidden(current.id(), hidden);
        Presence stored = presence.findActive(characterId).orElseThrow();
        eventInChat(gameId,
                hidden ? GameEventType.CHARACTER_STEALTHED : GameEventType.CHARACTER_REVEALED,
                actorUserId,
                current.chatId(),
                characterId,
                stealthPayload(characterId, current.chatId(), reason, hidden));
        if (hidden) {
            eventInChat(gameId, GameEventType.CHARACTER_APPEARED_LEFT, actorUserId, current.chatId(),
                    characterId, stealthPayload(characterId, current.chatId(), reason, true));
        }
        return stored;
    }

    private Map<String, Object> placementPayload(UUID characterId, Presence placed) {
        Map<String, Object> payload = payload("characterId", characterId.toString());
        payload.put("chatId", placed.chatId().toString());
        if (placed.hidden()) {
            payload.put("hidden", true);
        }
        return payload;
    }

    private Map<String, Object> stealthPayload(
            UUID characterId, UUID chatId, String reason, boolean hidden) {
        Map<String, Object> payload = payload("characterId", characterId.toString());
        payload.put("chatId", chatId.toString());
        payload.put("hidden", hidden);
        if (reason != null && !reason.isBlank()) {
            payload.put("reason", reason.strip());
        }
        return payload;
    }

    /**
     * Портрет обязан быть ассетом этой же игры: иначе через карточку персонажа можно было бы
     * сослаться на чужую картинку, а проверка участия на отдаче смотрит на игру ассета
     * (`D-075`), и карточка показывала бы 404 вместо отказа.
     */
    private void requirePortraitOfGame(UUID gameId, UUID portraitAssetId) {
        if (portraitAssetId == null) {
            return;
        }
        Asset asset = assets.findById(portraitAssetId)
                .orElseThrow(() -> ApiException.notFound("asset_not_found", "Ассет не найден"));
        if (!asset.gameId().equals(gameId)) {
            throw ApiException.badRequest("asset_other_game", "Ассет принадлежит другой игре");
        }
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

    /** Локация этой игры: чужая отдаётся как отсутствующая, чтобы не выдать её существование. */
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

    /** Персонаж этой игры; чужой отдаётся как отсутствующий, чтобы не выдать его существование. */
    private Character requireCharacter(UUID gameId, UUID characterId) {
        Character character = characters.findById(characterId)
                .orElseThrow(() -> ApiException.notFound("character_not_found", "Персонаж не найден"));
        if (!character.gameId().equals(gameId)) {
            throw ApiException.notFound("character_not_found", "Персонаж не найден");
        }
        return character;
    }

    private Transition requireTransition(UUID gameId, UUID transitionId) {
        Transition transition = transitions.findById(transitionId)
                .orElseThrow(() -> ApiException.notFound("transition_not_found", "Переход не найден"));
        if (!transition.gameId().equals(gameId)) {
            throw ApiException.notFound("transition_not_found", "Переход не найден");
        }
        return transition;
    }

    /** Хранилище этой игры; чужое отдаётся как отсутствующее, чтобы не выдать его существование. */
    private Storage requireStorage(UUID gameId, UUID storageId) {
        Storage storage = storages.findById(storageId)
                .orElseThrow(() -> ApiException.notFound("storage_not_found", "Хранилище не найдено"));
        if (!storage.gameId().equals(gameId)) {
            throw ApiException.notFound("storage_not_found", "Хранилище не найдено");
        }
        return storage;
    }

    /** Предмет этой игры; чужой отдаётся как отсутствующий, чтобы не выдать его существование. */
    private Item requireItem(UUID gameId, UUID itemId) {
        Item item = items.findById(itemId)
                .orElseThrow(() -> ApiException.notFound("item_not_found", "Предмет не найден"));
        if (!item.gameId().equals(gameId)) {
            throw ApiException.notFound("item_not_found", "Предмет не найден");
        }
        return item;
    }

    // --- Хранилища и предметы (E07) ----------------------------------------------------

    /**
     * Создание хранилища (`REQUIREMENTS §10`, `D-016`). Мастер заводит и локационное
     * хранилище с сундуком, и два хранилища персонажа (`D-110`).
     *
     * <p>Открывать хранилище при создании нельзя: доступ — отдельная мутация со своим
     * событием, иначе открытие не отличалось бы в журнале от появления хранилища.
     */
    @Transactional
    public Storage createStorage(
            UUID actorUserId,
            UUID gameId,
            StorageKind kind,
            UUID chatId,
            UUID characterId,
            String title,
            String description) {
        Game game = requireMaster(actorUserId, gameId);
        requireNotFinished(game, "content_change_after_finish", "После завершения игры мир не меняется");
        if (kind == null) {
            throw ApiException.badRequest("storage_kind_required", "Укажите тип хранилища");
        }
        if (kind.belongsToCharacter()) {
            if (characterId == null) {
                throw ApiException.badRequest("storage_character_required", "Укажите персонажа-владельца");
            }
            requireCharacter(gameId, characterId);
            if (chatId != null) {
                throw ApiException.badRequest("storage_owner_conflict", "Хранилище персонажа не привязано к локации");
            }
            if (storages.findByCharacterAndKind(characterId, kind).isPresent()) {
                throw ApiException.conflict("storage_already_exists", "У персонажа уже есть такое хранилище");
            }
        } else {
            if (chatId == null) {
                throw ApiException.badRequest("storage_chat_required", "Укажите локацию хранилища");
            }
            requireLocation(gameId, chatId);
            if (characterId != null) {
                throw ApiException.badRequest("storage_owner_conflict", "Локационное хранилище не принадлежит персонажу");
            }
        }
        if (title != null && !title.isBlank() && title.strip().length() > Storage.TITLE_MAX) {
            throw ApiException.badRequest(
                    "title_too_long", "Название хранилища: до " + Storage.TITLE_MAX + " знаков");
        }

        Storage storage = new Storage(
                Uuids.v7(),
                gameId,
                kind,
                chatId,
                characterId,
                normalize(title),
                normalize(description),
                null,
                false,
                Instant.now());
        storages.insert(storage);

        Map<String, Object> payload = payload("storageId", storage.id().toString());
        payload.put("kind", kind.name());
        if (chatId != null) {
            payload.put("chatId", chatId.toString());
        }
        if (characterId != null) {
            payload.put("characterId", characterId.toString());
        }
        eventInChat(gameId, GameEventType.STORAGE_CREATED, actorUserId, chatId, characterId, payload);
        return storage;
    }

    /** Правка названия и описания хранилища; открытость — отдельная мутация. */
    @Transactional
    public Storage updateStorage(
            UUID actorUserId, UUID gameId, UUID storageId, String title, String description) {
        Game game = requireMaster(actorUserId, gameId);
        requireNotFinished(game, "content_change_after_finish", "После завершения игры мир не меняется");
        Storage storage = requireStorage(gameId, storageId);
        if (title != null && !title.isBlank() && title.strip().length() > Storage.TITLE_MAX) {
            throw ApiException.badRequest(
                    "title_too_long", "Название хранилища: до " + Storage.TITLE_MAX + " знаков");
        }

        storages.updateContent(storageId, normalize(title), normalize(description));
        eventInChat(gameId, GameEventType.STORAGE_CONTENT_CHANGED, actorUserId, storage.chatId(), null,
                payload("storageId", storageId.toString()));
        return storages.findById(storage.id()).orElseThrow();
    }

    /**
     * Установка или снятие картинки хранилища (`D-098`). Прежняя снимается тем же вызовом:
     * `D-077` требует переключить указатель и отпустить старый, иначе замещённые файлы
     * занимают диск навсегда.
     */
    @Transactional
    public Storage setStorageImage(UUID actorUserId, UUID gameId, UUID storageId, UUID assetId) {
        Game game = requireMaster(actorUserId, gameId);
        requireNotFinished(game, "content_change_after_finish", "После завершения игры мир не меняется");
        Storage storage = requireStorage(gameId, storageId);

        if (assetId != null) {
            Asset asset = assets.findById(assetId)
                    .orElseThrow(() -> ApiException.notFound("asset_not_found", "Ассет не найден"));
            if (!asset.gameId().equals(gameId)) {
                throw ApiException.badRequest("asset_other_game", "Ассет принадлежит другой игре");
            }
        }

        UUID previous = storage.imageAssetId();
        storages.updateImage(storageId, assetId);

        Map<String, Object> payload = payload("storageId", storageId.toString());
        payload.put("assetId", assetId == null ? null : assetId.toString());
        eventInChat(gameId, GameEventType.STORAGE_CONTENT_CHANGED, actorUserId, storage.chatId(), null, payload);

        if (previous != null && !previous.equals(assetId)) {
            assetService.releaseIfUnreferenced(previous);
        }
        return storages.findById(storageId).orElseThrow();
    }

    /**
     * Открытие и закрытие хранилища (`REQUIREMENTS §10`, `D-110`). Одно событие с признаком
     * в `payload`, а не два типа: меняется одно поле, и «открыто» с «закрыто» не должны
     * расходиться по словарю.
     *
     * <p>Идемпотентно: повторная установка того же значения не пишет событие, потому что мир
     * не изменился.
     */
    @Transactional
    public Storage setStorageOpen(UUID actorUserId, UUID gameId, UUID storageId, boolean open) {
        Game game = requireMaster(actorUserId, gameId);
        requireNotFinished(game, "content_change_after_finish", "После завершения игры мир не меняется");
        Storage storage = requireStorage(gameId, storageId);
        if (storage.open() == open) {
            return storage;
        }

        storages.updateOpen(storageId, open);
        Map<String, Object> payload = payload("storageId", storageId.toString());
        payload.put("open", open);
        eventInChat(gameId, GameEventType.STORAGE_ACCESS_CHANGED, actorUserId, storage.chatId(), null, payload);
        return storages.findById(storageId).orElseThrow();
    }

    /**
     * Создание предмета сразу в хранилище (`REQUIREMENTS §10`). Предмета вне хранилища не
     * бывает: строка `item_placement` создаётся здесь же, и это держит уникальный индекс.
     *
     * <p>Количество ограничено типом: у неисчисляемого типа оно всегда `1` (`D-114`).
     */
    @Transactional
    public Item createItem(
            UUID actorUserId,
            UUID gameId,
            ItemType type,
            String name,
            String description,
            UUID imageAssetId,
            ItemSize size,
            Integer quantity,
            String properties,
            UUID storageId) {
        Game game = requireMaster(actorUserId, gameId);
        requireNotFinished(game, "content_change_after_finish", "После завершения игры мир не меняется");
        Storage storage = requireStorage(gameId, storageId);
        ItemType itemType = type == null ? ItemType.MISC : type;
        if (size == null) {
            throw ApiException.badRequest("item_size_required", "Укажите размер предмета");
        }
        requireText(name, "item_name_required", "Укажите название предмета", Item.NAME_MAX);
        int amount = requireQuantity(itemType, quantity);
        requireItemImageOfGame(gameId, imageAssetId);

        Instant now = Instant.now();
        Item item = new Item(
                Uuids.v7(),
                gameId,
                itemType,
                name.strip(),
                normalize(description),
                imageAssetId,
                size,
                amount,
                normalize(properties),
                now,
                now);
        items.insert(item, storageId);

        Map<String, Object> payload = payload("itemId", item.id().toString());
        payload.put("storageId", storageId.toString());
        payload.put("type", itemType.name());
        payload.put("size", size.name());
        payload.put("quantity", amount);
        eventInChat(gameId, GameEventType.ITEM_CREATED, actorUserId, chatIdOfStorage(storageId), null, payload);
        return item;
    }

    /** Правка карточки предмета. Размещение и количество через этот вызов не меняются. */
    @Transactional
    public Item updateItem(
            UUID actorUserId,
            UUID gameId,
            UUID itemId,
            ItemType type,
            String name,
            String description,
            ItemSize size,
            Integer quantity,
            String properties) {
        Game game = requireMaster(actorUserId, gameId);
        requireNotFinished(game, "content_change_after_finish", "После завершения игры мир не меняется");
        Item item = requireItem(gameId, itemId);
        ItemType itemType = type == null ? item.type() : type;
        ItemSize itemSize = size == null ? item.size() : size;
        requireText(name, "item_name_required", "Укажите название предмета", Item.NAME_MAX);
        int amount = requireQuantity(itemType, quantity == null ? item.quantity() : quantity);

        items.updateContent(
                itemId, itemType, name.strip(), normalize(description), itemSize, amount, normalize(properties));
        eventInChat(gameId, GameEventType.ITEM_CONTENT_CHANGED, actorUserId,
                chatIdOfStorage(items.findPlacement(itemId).map(p -> p.storageId()).orElse(null)), null,
                payload("itemId", itemId.toString()));
        return items.findById(itemId).orElseThrow();
    }

    /** Картинка предмета; прежняя отпускается тем же правилом, что у локации и хранилища (`D-077`). */
    @Transactional
    public Item setItemImage(UUID actorUserId, UUID gameId, UUID itemId, UUID assetId) {
        Game game = requireMaster(actorUserId, gameId);
        requireNotFinished(game, "content_change_after_finish", "После завершения игры мир не меняется");
        Item item = requireItem(gameId, itemId);
        requireItemImageOfGame(gameId, assetId);

        UUID previous = item.imageAssetId();
        items.updateImage(itemId, assetId);

        Map<String, Object> payload = payload("itemId", itemId.toString());
        payload.put("assetId", assetId == null ? null : assetId.toString());
        eventInChat(gameId, GameEventType.ITEM_CONTENT_CHANGED, actorUserId,
                chatIdOfStorage(items.findPlacement(itemId).map(p -> p.storageId()).orElse(null)), null, payload);

        if (previous != null && !previous.equals(assetId)) {
            assetService.releaseIfUnreferenced(previous);
        }
        return items.findById(itemId).orElseThrow();
    }

    /**
     * Перемещение предмета (`REQUIREMENTS §10`, `D-015`). Гонка разрешается блокировкой строки
     * предмета: пока транзакция держит строку, вторая видит уже применённый результат первой.
     *
     * <p>`fromStorageId` — ожидаемое текущее хранилище. Это и есть реализация `D-015`:
     * блокировка сама по себе не мешает второму запросу увезти предмет дальше, а условная
     * проверка источника отвечает «предмет уже забран» тому, кто читал состояние до победителя.
     * Мастер вправе не указывать источник и перенести предмет откуда угодно.
     *
     * <p>`quantity` меньше текущего количества отделяет часть стопки новой строкой: удалять
     * строки и оставлять нули нельзя (`D-010`, `D-114`). Полное взятие перемещает саму строку.
     */
    @Transactional
    public MoveResult moveItem(
            UUID actorUserId,
            UUID gameId,
            UUID itemId,
            UUID fromStorageId,
            UUID targetStorageId,
            Integer quantity) {
        Game game = requireMaster(actorUserId, gameId);
        requireNotFinished(game, "content_change_after_finish", "После завершения игры мир не меняется");
        if (targetStorageId == null) {
            throw ApiException.badRequest("storage_required", "Укажите хранилище назначения");
        }
        Storage target = requireStorage(gameId, targetStorageId);

        Item item = items.lockById(itemId)
                .orElseThrow(() -> ApiException.notFound("item_not_found", "Предмет не найден"));
        if (!item.gameId().equals(gameId)) {
            throw ApiException.notFound("item_not_found", "Предмет не найден");
        }
        ItemPlacement placement = items.findPlacement(itemId)
                .orElseThrow(() -> ApiException.conflict(
                        "item_without_storage", "Предмет вне хранилища — состояние недопустимо"));
        if (fromStorageId != null && !fromStorageId.equals(placement.storageId())) {
            throw ApiException.conflict("item_already_taken", "Предмет уже забран");
        }
        if (placement.storageId().equals(targetStorageId)) {
            throw ApiException.conflict("item_already_there", "Предмет уже в этом хранилище");
        }
        requireCapacity(target, item);

        int amount = quantity == null ? item.quantity() : requireQuantity(item.type(), quantity);
        if (amount > item.quantity()) {
            throw ApiException.badRequest("quantity_too_large", "Взять больше, чем есть, нельзя");
        }
        if (amount < item.quantity() && !item.type().countable()) {
            throw ApiException.badRequest(
                    "item_not_countable", "Из этого предмета нельзя взять часть");
        }

        Map<String, Object> payload = payload("itemId", itemId.toString());
        payload.put("storageId", targetStorageId.toString());
        payload.put("fromStorageId", placement.storageId().toString());
        payload.put("quantity", amount);

        Item moved;
        if (amount == item.quantity()) {
            items.movePlacement(itemId, targetStorageId);
            moved = items.findById(itemId).orElseThrow();
        } else {
            items.updateQuantity(itemId, item.quantity() - amount);
            moved = copyOf(item, amount);
            items.insert(moved, targetStorageId);
            payload.put("newItemId", moved.id().toString());
        }

        eventInChat(gameId, GameEventType.ITEM_MOVED, actorUserId, target.chatId(), null, payload);
        return new MoveResult(moved, storages.findById(placement.storageId()).orElse(null), target);
    }

    /**
     * Вместимость (`D-111`). Ограничение адресовано игроку и касается только двух хранилищ
     * персонажа; мастер не ограничен нигде (`D-015`).
     *
     * <p>Ветка оживает вместе с занятостью (`E13`, `D-109`): сейчас все мутации ведёт мастер,
     * и правило сюда не доходит. Оно стоит здесь, а не в `E13`, чтобы не разойтись с кодом
     * перемещения, а его истинность держит тест этапа через {@link PlacementRules}.
     */
    private void requireCapacity(Storage target, Item item) {
        if (target.kind().belongsToCharacter() && !PlacementRules.fits(target.kind(), item.size())) {
            throw ApiException.badRequest(
                    "item_does_not_fit",
                    item.size() == ItemSize.LARGE
                            ? "Крупный предмет не помещается в мелкое хранилище персонажа"
                            : "Мелкий предмет не кладётся в крупное хранилище персонажа");
        }
    }

    /** Отделённая часть стопки: та же карточка, другое количество и своя строка (`D-114`). */
    private static Item copyOf(Item source, int quantity) {
        Instant now = Instant.now();
        return new Item(
                Uuids.v7(),
                source.gameId(),
                source.type(),
                source.name(),
                source.description(),
                source.imageAssetId(),
                source.size(),
                quantity,
                source.properties(),
                now,
                now);
    }

    private int requireQuantity(ItemType type, Integer quantity) {
        int amount = quantity == null ? 1 : quantity;
        if (amount < 1) {
            throw ApiException.badRequest("quantity_invalid", "Количество не может быть меньше единицы");
        }
        if (!type.countable() && amount != 1) {
            throw ApiException.badRequest(
                    "item_not_countable", "У этого типа предмета количество всегда единица");
        }
        return amount;
    }

    private void requireItemImageOfGame(UUID gameId, UUID imageAssetId) {
        if (imageAssetId == null) {
            return;
        }
        Asset asset = assets.findById(imageAssetId)
                .orElseThrow(() -> ApiException.notFound("asset_not_found", "Ассет не найден"));
        if (!asset.gameId().equals(gameId)) {
            throw ApiException.badRequest("asset_other_game", "Ассет принадлежит другой игре");
        }
    }


    /** Чат хранилища: у локационного он есть, у хранилища персонажа — нет. */
    private UUID chatIdOfStorage(UUID storageId) {
        return storageId == null
                ? null
                : storages.findById(storageId).map(Storage::chatId).orElse(null);
    }

    // --- Ограничения (E08) -----------------------------------------------------------

    /**
     * Навешивание ограничения (`REQUIREMENTS §13`, `D-064`). Причина обязательна: без неё
     * через месяц игры никто не помнит, почему персонаж немой (`D-064`), а срок
     * поддерживается, но не обязателен.
     *
     * <p>Субъект ограничения диктуется его видом: `NO_JOKE` целится в участие, потому что
     * абьюзят шутками игроки, а не персонажи (`D-099`); остальные — в персонажа. Проверять
     * это на входе, а не полагаться на клиента, обязательно: иначе запрет шуток лёг бы на
     * персонажа и не сработал бы на участие.
     *
     * <p>Ограничение видно тому, кого оно касается, и мастеру: оно проецируется системным
     * сообщением в ленту того чата, где субъект стоит (`D-031`), — иначе игрок узнаёт о
     * немоем персонаже только по отказу при попытке заговорить.
     */
    @Transactional
    public Restriction setRestriction(
            UUID actorUserId,
            UUID gameId,
            SubjectKind subjectKind,
            UUID subjectId,
            RestrictionKind kind,
            String reason,
            Instant expiresAt) {
        requireMaster(actorUserId, gameId);
        if (kind == null) {
            throw ApiException.badRequest("restriction_kind_required", "Укажите вид ограничения");
        }
        if (subjectKind == null || subjectId == null) {
            throw ApiException.badRequest("restriction_subject_required", "Укажите, кому адресован запрет");
        }
        if (subjectKind != kind.subjectKind()) {
            throw ApiException.badRequest(
                    "restriction_subject_mismatch",
                    kind == RestrictionKind.NO_JOKE
                            ? "Запрет шуток адресуется участию, а не персонажу"
                            : "Этот запрет адресуется персонажу");
        }
        requireText(reason, "restriction_reason_required", "Укажите причину запрета", Restriction.REASON_MAX);

        InboxSubject subject = new InboxSubject(subjectKind, subjectId);
        UUID chatId = requireRestrictionSubject(gameId, subject);

        Restriction restriction = new Restriction(
                Uuids.v7(),
                gameId,
                subject,
                kind,
                reason.strip(),
                actorUserId,
                Instant.now(),
                expiresAt,
                null);
        restrictions.insert(restriction);

        Map<String, Object> payload = payload("restrictionId", restriction.id().toString());
        payload.put("subjectKind", subjectKind.name());
        payload.put("subjectId", subjectId.toString());
        payload.put("kind", kind.name());
        if (chatId != null) {
            payload.put("chatId", chatId.toString());
        }
        eventInChat(gameId, GameEventType.RESTRICTION_SET, actorUserId, chatId,
                subjectKind == SubjectKind.CHARACTER ? subjectId : null, payload);
        return restriction;
    }

    /** Снятие — штатная операция мастера, а не удаление: строка остаётся частью истории (`D-064`). */
    @Transactional
    public Restriction revokeRestriction(UUID actorUserId, UUID gameId, UUID restrictionId) {
        requireMaster(actorUserId, gameId);
        Restriction restriction = restrictions.findById(restrictionId)
                .orElseThrow(() -> ApiException.notFound("restriction_not_found", "Запрет не найден"));
        if (!restriction.gameId().equals(gameId)) {
            throw ApiException.notFound("restriction_not_found", "Запрет не найден");
        }
        if (restriction.revokedAt() != null) {
            return restriction;
        }

        restrictions.revoke(restrictionId);
        Restriction stored = restrictions.findById(restrictionId).orElseThrow();
        UUID chatId = requireRestrictionSubject(gameId, stored.subject());

        Map<String, Object> payload = payload("restrictionId", restrictionId.toString());
        payload.put("kind", stored.kind().name());
        eventInChat(gameId, GameEventType.RESTRICTION_REVOKED, actorUserId, chatId,
                stored.subject().kind() == SubjectKind.CHARACTER ? stored.subject().id() : null, payload);
        return stored;
    }

    /**
     * Субъект запрета обязан существовать в этой игре, а чат возвращается для проекции:
     * у персонажа это место, где он стоит; у участия — нет, потому что у наблюдателя
     * персонажа нет и ленты у него тоже (`D-063`).
     */
    private UUID requireRestrictionSubject(UUID gameId, InboxSubject subject) {
        if (subject.kind() == SubjectKind.CHARACTER) {
            requireCharacter(gameId, subject.id());
            return presence.findActive(subject.id()).map(Presence::chatId).orElse(null);
        }
        Membership membership = memberships.findByGame(gameId).stream()
                .filter(candidate -> candidate.id().equals(subject.id()))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("membership_not_found", "Участие не найдено"));
        if (membership.role() == MembershipRole.OBSERVER) {
            throw ApiException.badRequest(
                    "observer_has_no_character", "Наблюдатель не пишет, запрет шуток ему не нужен");
        }
        return null;
    }

    // --- Личные каналы (E11) -----------------------------------------------------------

    /**
     * Радиочат (`REQUIREMENTS §7.2`). Список персонажей задаёт мастер, и это не украшение:
     * радио связывает фиксированный круг, а не место, поэтому участники не обязаны быть
     * в одной локации (`D-039`).
     */
    @Transactional
    public Chat createRadio(UUID actorUserId, UUID gameId, String title, List<UUID> characterIds) {
        Game game = requireMaster(actorUserId, gameId);
        requireNotFinished(game, "content_change_after_finish", "После завершения игры мир не меняется");
        requireText(title, "title_required", "Укажите название радиочата", Chat.TITLE_MAX);

        UUID masterMembershipId = memberships.find(gameId, actorUserId).map(Membership::id)
                .orElseThrow(() -> ApiException.forbidden("not_master", "Изменять мир может только мастер"));
        Chat chat = insertChannel(gameId, ChatKind.RADIO, title.strip());
        for (UUID characterId : requireChannelCharacters(gameId, characterIds)) {
            channelMembers.insert(ChatMember.joined(
                    Uuids.v7(), chat.id(), InboxSubject.character(characterId), masterMembershipId));
        }
        Map<String, Object> payload = channelPayload(chat);
        payload.put("characterIds", characterIds.stream().map(Object::toString).toList());
        eventInChat(gameId, GameEventType.CHAT_CREATED, actorUserId, chat.id(), null, payload);
        return chat;
    }

    /**
     * Замена списка участников радиочата (`REQUIREMENTS §7.2`). Набор, а не добавление:
     * мастер ведёт фиксированный круг, и «убрать одного» — такая же правка, как «добавить».
     */
    @Transactional
    public Chat setRadioMembers(UUID actorUserId, UUID gameId, UUID chatId, List<UUID> characterIds) {
        Game game = requireMaster(actorUserId, gameId);
        requireNotFinished(game, "content_change_after_finish", "После завершения игры мир не меняется");
        Chat chat = requireChatOfKind(gameId, chatId, ChatKind.RADIO);
        UUID masterMembershipId = memberships.find(gameId, actorUserId).map(Membership::id).orElseThrow();
        Set<UUID> wanted = new LinkedHashSet<>(requireChannelCharacters(gameId, characterIds));

        for (ChatMember member : channelMembers.findActiveByChat(chatId)) {
            if (member.state() != ChatMemberState.JOINED
                    || member.subject().kind() != SubjectKind.CHARACTER
                    || wanted.contains(member.subject().id())) {
                continue;
            }
            channelMembers.close(member.id(), ChatMemberState.LEFT);
            event(gameId, GameEventType.CHAT_MEMBER_LEFT, actorUserId,
                    memberPayload(chatId, member.subject()));
        }
        Set<UUID> present = new LinkedHashSet<>();
        for (ChatMember member : channelMembers.findActiveByChat(chatId)) {
            if (member.state() == ChatMemberState.JOINED
                    && member.subject().kind() == SubjectKind.CHARACTER) {
                present.add(member.subject().id());
            }
        }
        for (UUID characterId : wanted) {
            if (present.contains(characterId)) {
                continue;
            }
            channelMembers.insert(ChatMember.joined(
                    Uuids.v7(), chatId, InboxSubject.character(characterId), masterMembershipId));
            event(gameId, GameEventType.CHAT_MEMBER_JOINED, actorUserId,
                    memberPayload(chatId, InboxSubject.character(characterId)));
        }
        return requireChatOfKind(gameId, chatId, ChatKind.RADIO);
    }

    /**
     * Отключение радиочата (`REQUIREMENTS §7.2`, `D-039`). Отключённый чат остаётся
     * читаемым: история связи — часть игры, и выключатель её не стирает (`D-010`).
     */
    @Transactional
    public Chat setRadioDisabled(UUID actorUserId, UUID gameId, UUID chatId, boolean disabled) {
        Game game = requireMaster(actorUserId, gameId);
        requireNotFinished(game, "content_change_after_finish", "После завершения игры мир не меняется");
        Chat chat = requireChatOfKind(gameId, chatId, ChatKind.RADIO);
        if (chat.disabled() == disabled) {
            return chat;
        }
        chats.setDisabled(chatId, disabled);
        Map<String, Object> payload = payload("chatId", chatId.toString());
        payload.put("disabled", disabled);
        eventInGame(gameId, GameEventType.CHAT_ACCESS_CHANGED, actorUserId, chatId, payload);
        return requireChatOfKind(gameId, chatId, ChatKind.RADIO);
    }

    /**
     * Шёпот (`REQUIREMENTS §7.4`, `D-038`, `D-097`). Условия проверяются в момент
     * приглашения: одна локация, одинаковая скрытность, разрешение говорить. Инициатор
     * входит сразу, остальные — по согласию, поэтому «приглашён» и «внутри» разные состояния.
     */
    @Transactional
    public Chat createWhisper(UUID actorUserId, UUID gameId, List<UUID> characterIds) {
        Game game = requireGame(gameId);
        requireNotFinished(game, "content_change_after_finish", "После завершения игры мир не меняется");
        Membership actor = memberships.find(gameId, actorUserId)
                .orElseThrow(() -> ApiException.forbidden("not_a_member", "Нет участия в этой игре"));
        if (!actor.joined() || actor.banned()) {
            throw ApiException.forbidden("membership_not_joined", "Участие в этой игре не активно");
        }
        List<UUID> wanted = requireChannelCharacters(gameId, characterIds);
        if (wanted.isEmpty()) {
            throw ApiException.badRequest("whisper_empty", "Шёпот без участников не нужен");
        }

        List<Character> speakers = new ArrayList<>();
        for (UUID characterId : wanted) {
            speakers.add(requireCharacter(gameId, characterId));
        }
        boolean initiatorInWhisper = speakers.stream()
                .anyMatch(character -> actor.id().equals(character.occupantMembershipId()));
        if (!initiatorInWhisper) {
            throw ApiException.forbidden(
                    "whisper_initiator_not_invited", "Шёпот начинают от лица своего персонажа");
        }
        Set<UUID> players = speakers.stream()
                .map(Character::occupantMembershipId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (players.size() < 2) {
            throw ApiException.badRequest(
                    "whisper_players", "В шёпоте нужны персонажи разных игроков");
        }
        assertWhisperConditions(gameId, speakers);

        Chat chat = insertChannel(gameId, ChatKind.WHISPER, CHANNEL_WHISPER_TITLE);
        for (Character speaker : speakers) {
            boolean mine = actor.id().equals(speaker.occupantMembershipId());
            channelMembers.insert(mine
                    ? ChatMember.joined(Uuids.v7(), chat.id(), InboxSubject.character(speaker.id()), actor.id())
                    : ChatMember.invited(Uuids.v7(), chat.id(), InboxSubject.character(speaker.id()), actor.id()));
        }
        // Мастер состоит по умолчанию (`D-038`): шёпот для него — не тайна, а часть надзора.
        channelMembers.insert(ChatMember.joined(
                Uuids.v7(), chat.id(), InboxSubject.membership(masterMembershipId(gameId, game)), actor.id()));

        Map<String, Object> payload = channelPayload(chat);
        payload.put("characterIds", wanted.stream().map(Object::toString).toList());
        eventInChat(gameId, GameEventType.CHAT_CREATED, actorUserId, chat.id(), null, payload);
        for (ChatMember member : channelMembers.findActiveByChat(chat.id())) {
            if (member.state() == ChatMemberState.INVITED) {
                event(gameId, GameEventType.CHAT_MEMBER_INVITED, actorUserId,
                        memberPayload(chat.id(), member.subject()));
            }
        }
        return chat;
    }

    /** Согласие на участие в шёпоте (`REQUIREMENTS §7.4`): приглашение становится входом. */
    @Transactional
    public Chat acceptWhisper(UUID actorUserId, UUID gameId, UUID chatId) {
        Membership actor = requireWhisperActor(gameId, actorUserId, chatId);
        List<ChatMember> invitations = invitationsOf(gameId, chatId, actor);
        if (invitations.isEmpty()) {
            throw ApiException.notFound("whisper_invitation_not_found", "Приглашения в этот шёпот нет");
        }
        for (ChatMember invitation : invitations) {
            channelMembers.join(invitation.id());
            event(gameId, GameEventType.CHAT_MEMBER_JOINED, actorUserId,
                    memberPayload(chatId, invitation.subject()));
        }
        return requireChatOfKind(gameId, chatId, ChatKind.WHISPER);
    }

    /** Отказ от приглашения: `DECLINED`, а не тишина, — иначе приглашающий ждал бы вечно. */
    @Transactional
    public Chat declineWhisper(UUID actorUserId, UUID gameId, UUID chatId) {
        Membership actor = requireWhisperActor(gameId, actorUserId, chatId);
        List<ChatMember> invitations = invitationsOf(gameId, chatId, actor);
        if (invitations.isEmpty()) {
            throw ApiException.notFound("whisper_invitation_not_found", "Приглашения в этот шёпот нет");
        }
        for (ChatMember invitation : invitations) {
            channelMembers.close(invitation.id(), ChatMemberState.DECLINED);
            event(gameId, GameEventType.CHAT_MEMBER_DECLINED, actorUserId,
                    memberPayload(chatId, invitation.subject()));
        }
        return requireChatOfKind(gameId, chatId, ChatKind.WHISPER);
    }

    /**
     * Закрытие шёпота участником или мастером (`D-038`). Участия закрываются, а чат
     * архивируется: шёпот кончился, но история остаётся читаемой (`D-010`).
     */
    @Transactional
    public Chat closeWhisper(UUID actorUserId, UUID gameId, UUID chatId) {
        Game game = requireGame(gameId);
        Membership actor = memberships.find(gameId, actorUserId)
                .orElseThrow(() -> ApiException.forbidden("not_a_member", "Нет участия в этой игре"));
        Chat chat = requireChatOfKind(gameId, chatId, ChatKind.WHISPER);
        if (chat.archived()) {
            return chat;
        }
        boolean master = actor.role() == MembershipRole.MASTER && !actor.banned();
        boolean member = !invitationsOfAll(gameId, chatId, actor).isEmpty();
        if (!master && !member) {
            throw ApiException.forbidden("not_a_chat_member", "Этот игрок в шёпоте не участвует");
        }
        for (ChatMember active : channelMembers.findActiveByChat(chatId)) {
            channelMembers.close(active.id(), ChatMemberState.LEFT);
        }
        chats.markArchived(chatId, master ? "шёпот закрыт мастером" : "шёпот закрыт участником");
        eventInChat(gameId, GameEventType.CHAT_ARCHIVED, actorUserId, chatId, null,
                payload("chatId", chatId.toString()));
        return requireChatOfKind(gameId, chatId, ChatKind.WHISPER);
    }

    /**
     * Чат финального обсуждения (`REQUIREMENTS §7.5`): один на игру, создаётся завершением,
     * принимает всех персонажей. Публичного списка участников у него нет, поэтому «все» —
     * это строки `chat_member` на каждого неархивного персонажа, а не правило на чтении
     * (`D-030`).
     */
    private Chat openFinalChat(Game game) {
        if (game.finalChatId() != null) {
            return chats.findById(game.finalChatId()).orElseThrow(() -> ApiException.conflict(
                    "final_chat_missing", "У завершённой игры нет чата финального обсуждения"));
        }
        Chat chat = insertChannel(game.id(), ChatKind.FINAL, CHANNEL_FINAL_TITLE);
        UUID masterMembershipId = masterMembershipId(game.id(), game);
        for (Character character : characters.findByGame(game.id())) {
            if (character.archived()) {
                continue;
            }
            channelMembers.insert(ChatMember.joined(
                    Uuids.v7(), chat.id(), InboxSubject.character(character.id()), masterMembershipId));
        }
        channelMembers.insert(ChatMember.joined(
                Uuids.v7(), chat.id(), InboxSubject.membership(masterMembershipId), masterMembershipId));
        games.updateFinalChat(game.id(), chat.id());
        return chat;
    }

    /** Личный чат с мастером на участие (`D-037`): заводится входом в игру, а не мастером. */
    private void openMasterDm(Game game, Membership membership) {
        if (membership.role() != MembershipRole.PLAYER
                || membership.status() != MembershipStatus.JOINED) {
            return;
        }
        InboxSubject subject = InboxSubject.membership(membership.id());
        boolean exists = channelMembers.findActiveBySubject(game.id(), subject).stream()
                .map(ChatMember::chatId)
                .map(chats::findById)
                .flatMap(java.util.Optional::stream)
                .anyMatch(chat -> chat.kind() == ChatKind.MASTER_DM);
        if (exists) {
            return;
        }
        Chat chat = insertChannel(game.id(), ChatKind.MASTER_DM, CHANNEL_MASTER_DM_TITLE);
        UUID masterMembershipId = masterMembershipId(game.id(), game);
        channelMembers.insert(ChatMember.joined(Uuids.v7(), chat.id(), subject, masterMembershipId));
        // Мастер — вторая сторона разговора, а не надзиратель: он и читает, и отвечает
        // (`REQUIREMENTS §7.3`). Без его строки участия ответ мастера отклонялся бы как
        // запись в чужой канал, и личный чат оказался бы улицей с односторонним движением.
        channelMembers.insert(ChatMember.joined(
                Uuids.v7(), chat.id(), InboxSubject.membership(masterMembershipId), masterMembershipId));
        Map<String, Object> payload = channelPayload(chat);
        payload.put("membershipId", membership.id().toString());
        eventInChat(game.id(), GameEventType.CHAT_CREATED, membership.userId(), chat.id(), null, payload);
    }

    /** Проверки шёпота в момент приглашения: одна локация, одна скрытность, право говорить. */
    private void assertWhisperConditions(UUID gameId, List<Character> speakers) {
        UUID location = null;
        boolean hidden = false;
        for (Character speaker : speakers) {
            Presence placement = presence.findActive(speaker.id())
                    .orElseThrow(() -> ApiException.conflict(
                            "whisper_same_location",
                            "Шептаться можно только там, где персонаж находится"));
            if (location == null) {
                location = placement.chatId();
                hidden = placement.hidden();
            } else if (!location.equals(placement.chatId())) {
                throw ApiException.conflict(
                        "whisper_same_location", "Собеседники должны быть в одной локации");
            } else if (hidden != placement.hidden()) {
                throw ApiException.conflict(
                        "whisper_same_stealth", "Скрытность собеседников должна совпадать");
            }
            boolean muted = restrictions.findNotRevoked(gameId, InboxSubject.character(speaker.id())).stream()
                    .anyMatch(restriction -> restriction.kind() == RestrictionKind.NO_SPEECH
                            && restriction.activeAt(Instant.now()));
            if (muted) {
                throw ApiException.conflict(
                        "whisper_speech_restricted", "Собеседнику запрещена речь");
            }
        }
    }

    private List<UUID> requireChannelCharacters(UUID gameId, List<UUID> characterIds) {
        if (characterIds == null || characterIds.isEmpty()) {
            return List.of();
        }
        Set<UUID> distinct = new LinkedHashSet<>(characterIds);
        if (distinct.size() != characterIds.size()) {
            throw ApiException.badRequest("channel_member_duplicate", "Персонаж указан дважды");
        }
        for (UUID characterId : distinct) {
            Character character = requireCharacter(gameId, characterId);
            if (character.archived()) {
                throw ApiException.conflict("character_archived", "Персонаж выведен из игры");
            }
        }
        return List.copyOf(distinct);
    }

    /** Приглашения участия в шёпоте: персонажи, которыми игрок играет, и его участие. */
    private List<ChatMember> invitationsOf(UUID gameId, UUID chatId, Membership actor) {
        return invitationsOfAll(gameId, chatId, actor).stream()
                .filter(member -> member.state() == ChatMemberState.INVITED)
                .toList();
    }

    private List<ChatMember> invitationsOfAll(UUID gameId, UUID chatId, Membership actor) {
        Set<UUID> mine = characters.findByGame(gameId).stream()
                .filter(character -> actor.id().equals(character.occupantMembershipId()))
                .map(Character::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return channelMembers.findActiveByChat(chatId).stream()
                .filter(member -> switch (member.subject().kind()) {
                    case CHARACTER -> mine.contains(member.subject().id());
                    case MEMBERSHIP -> actor.id().equals(member.subject().id());
                })
                .toList();
    }

    private Membership requireWhisperActor(UUID gameId, UUID actorUserId, UUID chatId) {
        requireGame(gameId);
        requireChatOfKind(gameId, chatId, ChatKind.WHISPER);
        Membership actor = memberships.find(gameId, actorUserId)
                .orElseThrow(() -> ApiException.forbidden("not_a_member", "Нет участия в этой игре"));
        if (!actor.joined() || actor.banned()) {
            throw ApiException.forbidden("membership_not_joined", "Участие в этой игре не активно");
        }
        return actor;
    }

    /** Событие вне чата: отключение радио видно и в общем канале игры, а не только в ленте. */
    private void eventInGame(
            UUID gameId, GameEventType type, UUID actorUserId, UUID chatId, Map<String, Object> payload) {
        long seq = record(gameId, type, actorUserId, chatId, null, false, payload);
        projectSystem(gameId, type, chatId, seq);
    }

    private Chat insertChannel(UUID gameId, ChatKind kind, String title) {
        Instant now = Instant.now();
        Chat chat = new Chat(
                Uuids.v7(), gameId, kind, title, null, null, false, null, null, false, now);
        chats.insert(chat);
        return chat;
    }

    private Map<String, Object> channelPayload(Chat chat) {
        Map<String, Object> payload = payload("chatId", chat.id().toString());
        payload.put("kind", chat.kind().name());
        payload.put("title", chat.title());
        return payload;
    }

    private static Map<String, Object> memberPayload(UUID chatId, InboxSubject subject) {
        Map<String, Object> payload = payload("chatId", chatId.toString());
        payload.put("subjectKind", subject.kind().name());
        payload.put("subjectId", subject.id().toString());
        return payload;
    }

    private Chat requireChatOfKind(UUID gameId, UUID chatId, ChatKind kind) {
        Chat chat = chats.findById(chatId)
                .orElseThrow(() -> ApiException.notFound("chat_not_found", "Чат не найден"));
        if (!chat.gameId().equals(gameId) || chat.kind() != kind) {
            throw ApiException.notFound("chat_not_found", "Чат не найден");
        }
        return chat;
    }

    private UUID masterMembershipId(UUID gameId, Game game) {
        return memberships.find(gameId, game.masterUserId())
                .map(Membership::id)
                .orElseThrow(() -> ApiException.conflict("master_absent", "У игры нет мастера"));
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
        record(gameId, type, actorUserId, null, null, false, payload);
    }

    /**
     * Единственная точка записи события: номер выдаётся счётчиком, а ссылка на событие
     * уходит в фан-аут после фиксации транзакции (`D-034`, `D-084`). Отдельный метод, чтобы
     * публикация не осталась забытой на одном из путей записи: без неё сокет молчит,
     * а журнал при этом полон.
     */
    private long record(
            UUID gameId,
            GameEventType type,
            UUID actorUserId,
            UUID chatId,
            UUID characterId,
            boolean onBehalfOfMaster,
            Map<String, Object> payload) {
        long seq = events.append(
                gameId, type, actorUserId, chatId, characterId, onBehalfOfMaster, payloads.write(payload));
        realtime.publishAfterCommit(gameId, seq);
        return seq;
    }

    /**
     * Событие, у которого есть место: чат, персонаж и признак «от лица мастера» попадают
     * в соответствующие колонки журнала (`D-005`), а само событие проецируется в ленту
     * этого чата системным сообщением (`D-031`). Отдельный метод, а не расширение
     * {@link #event}, потому что у события без чата колонка обязана остаться пустой:
     * `chat_id` читается потребителями журнала как «где это было».
     */
    private void eventInChat(
            UUID gameId,
            GameEventType type,
            UUID actorUserId,
            UUID chatId,
            UUID characterId,
            Map<String, Object> payload) {
        eventInChatSeq(gameId, type, actorUserId, chatId, characterId, payload);
    }

    /** Та же запись, но с возвратом номера: он нужен там, где номер события — часть ответа. */
    private long eventInChatSeq(
            UUID gameId,
            GameEventType type,
            UUID actorUserId,
            UUID chatId,
            UUID characterId,
            Map<String, Object> payload) {
        long seq = record(gameId, type, actorUserId, chatId, characterId, false, payload);
        projectSystem(gameId, type, chatId, seq);
        return seq;
    }

    /**
     * Проецирует событие в ленту чата системным сообщением (`D-031`). Словарь проекции
     * отсеивает служебные события: то, у чего нет смысла в ленте (выдача номера, правка
     * названия игры), остаётся только в журнале.
     */
    private void projectSystem(UUID gameId, GameEventType type, UUID chatId, long eventSeq) {
        if (chatId == null || !SystemProjection.projects(type)) {
            return;
        }
        Game game = games.findById(gameId).orElse(null);
        if (game != null) {
            inbox.materializeSystem(game, chatId, eventSeq, true);
        }
    }

    /**
     * Точка, которой пользуются будущие сервисы внутри уже открытой транзакции: тогда событие
     * и эффект не могут разъехаться по разным транзакциям. `MANDATORY` выбран вместо
     * `REQUIRED`, чтобы отсутствие внешней транзакции падало, а не создавалось молча новое
     * соединение — «одно горло» держится на этом (`D-034`).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long appendEvent(UUID gameId, GameEventType type, UUID actorUserId, Map<String, Object> payload) {
        return record(gameId, type, actorUserId, null, null, false, payload);
    }

    /**
     * Событие, у которого нет чужой транзакции: решение по заявке, её просрочка и откат.
     *
     * <p>Отличие от {@link #appendEvent} — только в распространении транзакции, и оно
     * принципиальное. Решение по заявке идёт <em>вне</em> транзакции: каждая операция
     * применяется своей, иначе первая неудачная откатила бы уже применённые (`D-137`).
     * `MANDATORY` в такой точке упал бы на отсутствии транзакции, а `REQUIRED` открывает
     * свою — журнал получает запись, не затягивая в транзакцию применение операций.
     */
    @Transactional
    public long appendStandaloneEvent(
            UUID gameId, GameEventType type, UUID actorUserId, Map<String, Object> payload) {
        return record(gameId, type, actorUserId, null, null, false, payload);
    }

    /**
     * Запись события сообщения: у него есть чат, автор-персонаж и признак «от лица
     * мастера» (`D-005`), а номер выдаётся тем же счётчиком, что и у прочих мутаций
     * (`D-033`). Вызывается из {@code MessageService} внутри его транзакции.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long appendEvent(
            UUID gameId,
            GameEventType type,
            UUID actorUserId,
            Map<String, Object> payload,
            UUID chatId,
            UUID characterId,
            boolean onBehalfOfMaster) {
        return record(gameId, type, actorUserId, chatId, characterId, onBehalfOfMaster, payload);
    }

    // --- Применение change set (E14) ----------------------------------------------------

    /**
     * Правка одного поля карточки заявкой (`PLAN` E14, задача 3). Отдельный метод, а не
     * {@code updateCharacterCard}, по двум причинам: карточка пишется целиком только из формы
     * мастера, а заявка называет ровно одно поле — писать остальные её значениями значило бы
     * затирать чужие правки, сделанные между созданием заявки и её одобрением. Вторая причина —
     * старое значение: без него откат `D-041` нечего восстанавливать.
     *
     * <p>Строка читается под `FOR UPDATE`, а не обычным чтением: правка мастера и одобрение
     * заявки на то же поле иначе теряли бы одну из записей (`D-034`, `D-100`).
     */
    @Transactional
    public Map<String, Object> applyCharacterFieldEdit(
            UUID actorUserId,
            UUID gameId,
            UUID characterId,
            CharacterField field,
            String value,
            UUID requestId,
            UUID operationId) {
        Game game = requireMaster(actorUserId, gameId);
        requireNotFinished(game, "content_change_after_finish", "После завершения игры мир не меняется");
        Character locked = characters.lockById(characterId)
                .orElseThrow(() -> ApiException.notFound("character_not_found", "Персонаж не найден"));
        if (!locked.gameId().equals(gameId)) {
            throw ApiException.notFound("character_not_found", "Персонаж не найден");
        }
        if (locked.archived()) {
            throw ApiException.conflict(
                    "character_archived", "Персонаж выведен из игры: правка невозможна");
        }
        String next = field == CharacterField.NAME
                ? requireCharacterName(value)
                : normalize(value);
        String previous = field.valueIn(locked);
        characters.updateField(characterId, field, next);

        Map<String, Object> payload = payload("characterId", characterId.toString());
        payload.put("field", field.wireName());
        payload.put("value", next);
        // Старое значение — объект, а не голое значение: откат обязан знать, какое поле
        // восстанавливает, иначе он не отличит пустую строку от «поле не названо».
        // Собирается изменяемой картой: `Map.of` не переживает null, а пустое поле — норма.
        Map<String, Object> oldValue = new LinkedHashMap<>();
        oldValue.put("field", field.wireName());
        oldValue.put("value", previous);
        payload.put("oldValue", oldValue);
        markChangeSet(payload, requestId, operationId);
        record(gameId, GameEventType.CHARACTER_CONTENT_CHANGED, actorUserId, null, null, false, payload);
        return oldValue;
    }

    /**
     * Перемещение персонажа по переходу по одобренной заявке (`PLAN` E14, задача 3). Мастер
     * одобряет просьбу игрока, но не отменяет состояние мира: ребро обязано быть `AVAILABLE`
     * и сейчас, иначе одобрение открыло бы закрытую дверь. Принудительное перемещение остаётся
     * отдельным жестом мастера (`PUT /location`), а не одобрением заявки.
     *
     * <p>Направление берётся у ребра, а не из payload: иначе заявка указывала бы «откуда»
     * и перемещение из локации, где персонажа нет, проходило бы проверку (`D-107`).
     */
    @Transactional
    public Map<String, Object> applyCharacterMove(
            UUID actorUserId,
            UUID gameId,
            UUID characterId,
            UUID transitionId,
            UUID requestId,
            UUID operationId) {
        Game game = requireMaster(actorUserId, gameId);
        requireNotFinished(game, "content_change_after_finish", "После завершения игры мир не меняется");
        Character character = requireCharacter(gameId, characterId);
        if (character.archived()) {
            throw ApiException.conflict(
                    "character_archived", "Персонаж выведен из игры: перемещение невозможно");
        }
        Transition transition = requireTransition(gameId, transitionId);
        if (transition.archived()) {
            throw ApiException.conflict("transition_archived", "Переход выведен из игры");
        }
        Presence current = presence.findActive(characterId)
                .orElseThrow(() -> ApiException.conflict(
                        "character_not_placed", "Персонаж нигде не находится: идти неоткуда"));
        if (!current.chatId().equals(transition.fromChatId())) {
            throw ApiException.conflict("not_at_transition", "Переход начинается не там, где вы стоите");
        }
        EdgeState edge = edgeStates.find(transitionId, characterId).map(TransitionState::state)
                .orElse(EdgeState.UNKNOWN);
        if (edge != EdgeState.AVAILABLE) {
            throw ApiException.forbidden(
                    "transition_unavailable", "Этот переход для вашего персонажа закрыт");
        }
        UUID fromChatId = current.chatId();
        Map<String, Object> oldValue = new LinkedHashMap<>();
        oldValue.put("chatId", fromChatId.toString());
        Map<String, Object> extras = new LinkedHashMap<>(oldValue);
        extras.put("oldValue", oldValue);
        extras.put("transitionId", transitionId.toString());
        markChangeSet(extras, requestId, operationId);
        placeCharacterInternal(game, actorUserId, characterId, transition.toChatId(), extras);
        return oldValue;
    }

    /**
     * Правка названия и описания хранилища заявкой (`PLAN` E14, задача 3). Открытость сюда
     * не входит: это доступ, а не содержимое, и у неё свой тип события (`D-110`).
     */
    @Transactional
    public Map<String, Object> applyStorageContentsEdit(
            UUID actorUserId,
            UUID gameId,
            UUID storageId,
            String title,
            String description,
            UUID requestId,
            UUID operationId) {
        Game game = requireMaster(actorUserId, gameId);
        requireNotFinished(game, "content_change_after_finish", "После завершения игры мир не меняется");
        Storage storage = requireStorage(gameId, storageId);
        String nextTitle = title == null ? storage.title() : normalize(title);
        String nextDescription = description == null ? storage.description() : normalize(description);
        if (nextTitle != null && nextTitle.length() > Storage.TITLE_MAX) {
            throw ApiException.badRequest(
                    "title_too_long", "Название хранилища: до " + Storage.TITLE_MAX + " знаков");
        }

        Map<String, Object> oldValue = new LinkedHashMap<>();
        oldValue.put("title", storage.title());
        oldValue.put("description", storage.description());
        storages.updateContent(storageId, nextTitle, nextDescription);

        Map<String, Object> payload = payload("storageId", storageId.toString());
        payload.put("title", nextTitle);
        payload.put("description", nextDescription);
        payload.put("oldValue", oldValue);
        markChangeSet(payload, requestId, operationId);
        eventInChatSeq(
                gameId, GameEventType.STORAGE_CONTENT_CHANGED, actorUserId, storage.chatId(), null, payload);
        return oldValue;
    }

    /**
     * Отметка заявки в payload события. Идентификаторы операции нужны откату, чтобы найти
     * прежнее значение в журнале, а не в отдельной таблице: журнал — источник истины (`D-033`).
     */
    private static void markChangeSet(
            Map<String, Object> payload, UUID requestId, UUID operationId) {
        if (requestId != null) {
            payload.put("requestId", requestId.toString());
        }
        if (operationId != null) {
            payload.put("operationId", operationId.toString());
        }
    }

    private static String requireCharacterName(String value) {
        requireText(value, "character_name_required", "Укажите имя персонажа", Character.NAME_MAX);
        return value.strip();
    }

    /**
     * Постановка персонажа в указанную локацию заявкой. Отдельно от {@code moveCharacter}
     * и от {@link #placeCharacter}: это откат перемещения, и он обязан вернуть персонажа
     * туда, где он был, даже если ребро за это время закрыли или архивировали. Проверять
     * направление перехода при отмене неуместно: отмена не путешествие, а восстановление
     * прежнего состояния — иначе откат необратимо застревал бы на мире, который изменился.
     */
    @Transactional
    public Map<String, Object> applyCharacterPlacement(
            UUID actorUserId,
            UUID gameId,
            UUID characterId,
            UUID chatId,
            UUID requestId,
            UUID operationId) {
        Game game = requireMaster(actorUserId, gameId);
        Map<String, Object> oldValue = new LinkedHashMap<>();
        presence.findActive(characterId).ifPresent(
                current -> oldValue.put("chatId", current.chatId().toString()));
        Map<String, Object> extras = new LinkedHashMap<>(oldValue);
        extras.put("oldValue", oldValue);
        markChangeSet(extras, requestId, operationId);
        placeCharacterInternal(game, actorUserId, characterId, chatId, extras);
        return oldValue;
    }

    /**
     * Проекция события в системное сообщение (`D-031`): событие мира, у которого чат —
     * это место происходящего, обязано появиться в ленте этого чата. Материализуются
     * только строки получателей; собственной строки в `message` у системного события нет,
     * тело собирается из `payload` при чтении.
     *
     * <p>Вызывается после {@code event(...)}, потому что нужен выданный `seq`. Тип события
     * проверяется по словарю проекции: служебное событие без чата в ленту не попадает.
     */
}
