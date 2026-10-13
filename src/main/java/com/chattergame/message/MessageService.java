package com.chattergame.message;

import com.chattergame.character.Character;
import com.chattergame.character.CharacterRepository;
import com.chattergame.character.Presence;
import com.chattergame.character.PresenceRepository;
import com.chattergame.chat.Chat;
import com.chattergame.chat.ChatKind;
import com.chattergame.chat.ChatRepository;
import com.chattergame.game.Game;
import com.chattergame.game.GameMutationService;
import com.chattergame.game.GameRepository;
import com.chattergame.game.Membership;
import com.chattergame.game.MembershipRepository;
import com.chattergame.journal.GameEventType;
import com.chattergame.journal.Payloads;
import com.chattergame.util.ApiException;
import com.chattergame.util.Uuids;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Запись сообщений (`PLAN` E08, задачи 2 и 3).
 *
 * <p>Порядок тот же, что у любой мутации мира (`D-034`): эффект, затем событие, и оба
 * в одной транзакции. Разница в том, что после события пишутся строки `inbox_entry`:
 * видимость материализуется при записи (`D-030`), и без неё read-путь читал бы журнал
 * мимо материала. Событие даёт `seq`, а `inbox_entry` ссылается именно на него (`D-083`).
 *
 * <p>Права проверяет {@link MessageAccess} по снимку {@link MessagePostingFactory}: снимок
 * берётся здесь, перед эффектом, и не переиспользуется между запросами (`D-065`).
 */
@Service
public class MessageService {

    private final MessageRepository messages;
    private final ReadCursorRepository cursors;
    private final MessagePostingFactory postings;
    private final InboxMaterializer inbox;
    private final GameMutationService mutations;
    private final GameRepository games;
    private final MembershipRepository memberships;
    private final ChatRepository chats;
    private final CharacterRepository characters;
    private final PresenceRepository presence;
    private final Payloads payloads;

    public MessageService(
            MessageRepository messages,
            ReadCursorRepository cursors,
            MessagePostingFactory postings,
            InboxMaterializer inbox,
            GameMutationService mutations,
            GameRepository games,
            MembershipRepository memberships,
            ChatRepository chats,
            CharacterRepository characters,
            PresenceRepository presence,
            Payloads payloads) {
        this.messages = messages;
        this.cursors = cursors;
        this.postings = postings;
        this.inbox = inbox;
        this.mutations = mutations;
        this.games = games;
        this.memberships = memberships;
        this.chats = chats;
        this.characters = characters;
        this.presence = presence;
        this.payloads = payloads;
    }

    /**
     * Запись сообщения в чат. Тело обязательно, кроме `SYSTEM`, который сюда не попадает
     * вовсе: системные события генерирует система, а не игрок (`D-031`).
     *
     * <p>Скрытность автора фиксируется в `payload` на момент записи: `D-007` требует, чтобы
     * спрятавшийся после публичных действий отображался как ушедший и прошлые реплики
     * оставались, — то есть видимость сообщения не пересчитывается задним числом.
     */
    @Transactional
    public Message post(
            UUID userId,
            UUID gameId,
            UUID chatId,
            UUID characterId,
            MessageKind kind,
            String body,
            boolean onBehalfOfMaster) {
        Chat chat = requireChat(gameId, chatId);
        MessageAccess.assertBodyLength(body);
        MessageAccess.assertCanPost(postings.posting(userId, gameId, chatId, characterId), kind);

        Game game = games.findById(gameId).orElseThrow();
        boolean hidden = hiddenAuthor(characterId);
        Map<String, Object> payload = new LinkedHashMap<>();
        if (hidden) {
            payload.put("hiddenAuthor", true);
        }
        if (onBehalfOfMaster) {
            payload.put("onBehalfOfMaster", true);
        }
        // Бюджет хода — пока только поле события (механика — E10, PLAN E08 п. 7).
        if (kind == MessageKind.SPEECH || kind == MessageKind.ACTION) {
            payload.put("budgetConsumed", !isMasterDm(chat) && characterId != null);
        }

        Message message = new Message(
                Uuids.v7(),
                gameId,
                chatId,
                // seq проставит событие; объект пересобирается после записи (ниже).
                0L,
                kind,
                characterId,
                membershipId(game, userId),
                userId,
                onBehalfOfMaster,
                body == null ? null : body.strip(),
                payload,
                Instant.now());

        long seq = mutations.appendEvent(
                gameId,
                GameEventType.MESSAGE_POSTED,
                userId,
                messagePayload(message, chat.kind()),
                chatId,
                characterId,
                onBehalfOfMaster);

        Message stored = withSeq(message, seq);
        messages.insert(stored);
        inbox.materializePost(
                game,
                chatId,
                seq,
                stored.id(),
                userId,
                characterId,
                hidden,
                kind == MessageKind.JOKE);
        return stored;
    }

    /**
     * Отметка «прочитано до». Субъект — тот же, которым читают ленту: персонаж или участие
     * наблюдателя (`D-086`). Курсор двигается только вперёд, отметка назад не откатывает.
     */
    @Transactional
    public Map<String, Object> markRead(UUID userId, UUID gameId, UUID chatId, UUID asCharacter, long seq) {
        Chat chat = requireChat(gameId, chatId);
        InboxSubject subject = readSubject(gameId, userId, asCharacter);
        long target = seq <= 0 ? messages.maxEventSeqInChat(chatId) : seq;
        // отметка не может обогнать ленту: курсор за пределами выданного дал бы «прочитано
        // больше, чем показано», и клиент потерял бы строки при следующем чтении
        long cap = messages.maxEventSeqInChat(chatId);
        cursors.advance(subject, chatId, Math.min(target, cap));
        return Map.of(
                "chatId", chatId.toString(),
                "subjectKind", subject.kind().name(),
                "subjectId", subject.id().toString(),
                "lastSeq", Math.min(target, cap));
    }

    /**
     * Вход в локацию (`D-032`): ставит курсор на текущий максимум и отдаёт последнее
     * сообщение. Всё, что было до входа, читается прочитанным — туман войны сохраняется,
     * и метазнание из комнаты, куда вошли, не вываливается простыней.
     */
    @Transactional
    public Map<String, Object> enterChat(UUID userId, UUID gameId, UUID chatId, UUID asCharacter) {
        Chat chat = requireChat(gameId, chatId);
        InboxSubject subject = readSubject(gameId, userId, asCharacter);
        long max = messages.maxEventSeqInChat(chatId);
        cursors.advance(subject, chatId, max);
        FeedItem last = messages.lastFor(gameId, subject, chatId).orElse(null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("chatId", chatId.toString());
        result.put("chatKind", chat.kind().name());
        result.put("cursor", max);
        result.put("lastMessage", last);
        return result;
    }

    private InboxSubject readSubject(UUID gameId, UUID userId, UUID asCharacter) {
        Game game = games.findById(gameId)
                .orElseThrow(() -> ApiException.notFound("game_not_found", "Игра не найдена"));
        Membership membership = memberships.find(gameId, userId)
                .orElseThrow(() -> ApiException.forbidden("not_a_member", "Нет доступа к этой игре"));
        if (membership.role() == com.chattergame.game.MembershipRole.OBSERVER) {
            if (asCharacter != null) {
                throw ApiException.forbidden("observer_has_no_character", "У наблюдателя нет персонажа");
            }
            return InboxSubject.membership(membership.id());
        }
        if (asCharacter == null) {
            throw ApiException.badRequest(
                    "character_required", "Укажите персонажа, чьими глазами читаете");
        }
        Character character = characters.findById(asCharacter)
                .orElseThrow(() -> ApiException.notFound("character_not_found", "Персонаж не найден"));
        if (!character.gameId().equals(gameId)) {
            throw ApiException.notFound("character_not_found", "Персонаж не найден");
        }
        if (membership.role() != com.chattergame.game.MembershipRole.MASTER
                && !membership.id().equals(character.occupantMembershipId())) {
            throw ApiException.forbidden("not_your_character", "Это чужой персонаж");
        }
        return InboxSubject.character(asCharacter);
    }

    private Map<String, Object> messagePayload(Message message, ChatKind kind) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageId", message.id().toString());
        payload.put("kind", message.kind().name());
        payload.put("chatKind", kind.name());
        if (message.characterId() != null) {
            payload.put("characterId", message.characterId().toString());
        }
        if (message.authorMembershipId() != null) {
            payload.put("authorMembershipId", message.authorMembershipId().toString());
        }
        message.payload().forEach(payload::putIfAbsent);
        return payload;
    }

    private static Message withSeq(Message message, long seq) {
        return new Message(
                message.id(),
                message.gameId(),
                message.chatId(),
                seq,
                message.kind(),
                message.characterId(),
                message.authorMembershipId(),
                message.authorUserId(),
                message.onBehalfOfMaster(),
                message.body(),
                message.payload(),
                message.createdAt());
    }

    /** Скрытность берётся у активного присутствия автора: она состояние присутствия, не карточки. */
    private boolean hiddenAuthor(UUID characterId) {
        if (characterId == null) {
            return false;
        }
        return presence.findActive(characterId).map(Presence::hidden).orElse(false);
    }

    private UUID membershipId(Game game, UUID userId) {
        return memberships.find(game.id(), userId).map(Membership::id).orElse(null);
    }

    private static boolean isMasterDm(Chat chat) {
        return chat.kind() == ChatKind.MASTER_DM;
    }

    private Chat requireChat(UUID gameId, UUID chatId) {
        Chat chat = chats.findById(chatId)
                .orElseThrow(() -> ApiException.notFound("chat_not_found", "Чат не найден"));
        if (!chat.gameId().equals(gameId)) {
            throw ApiException.notFound("chat_not_found", "Чат не найден");
        }
        return chat;
    }
}
