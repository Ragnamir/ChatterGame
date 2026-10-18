package com.chattergame.notification;

import com.chattergame.character.Character;
import com.chattergame.character.CharacterRepository;
import com.chattergame.character.Presence;
import com.chattergame.character.PresenceRepository;
import com.chattergame.chat.Chat;
import com.chattergame.chat.ChatKind;
import com.chattergame.chat.ChatMember;
import com.chattergame.chat.ChatMemberRepository;
import com.chattergame.chat.ChatRepository;
import com.chattergame.game.GameRepository;
import com.chattergame.game.Membership;
import com.chattergame.game.MembershipRepository;
import com.chattergame.game.MembershipRole;
import com.chattergame.game.MembershipStatus;
import com.chattergame.item.Storage;
import com.chattergame.item.StorageRepository;
import com.chattergame.journal.GameEvent;
import com.chattergame.message.InboxSubject;
import com.chattergame.message.SubjectKind;
import com.chattergame.message.SystemProjection;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Фильтр журнала в адресованные уведомления (`PLAN` O03, `D-148`).
 *
 * <p>Уведомление — читатель журнала, а не новый механизм (`D-070`): кандидаты выводятся
 * из уже записанных событий, и ни одно событие не пишется ради уведомления. Поэтому
 * добавление настоящего транспорта не потребует ни новой записи в журнал, ни новых строк
 * в `inbox_entry`.
 *
 * <p>Адресат вычисляется по правилам игры, а не по подписке клиента: приглашённый в шёпот,
 * игрок в личном чате, владелец хранилища. Рассылка не решает, кому нужно знать, — она
 * только спрашивает у человека канал (`D-030` по духу).
 *
 * <p>Скрытое не уведомляет: событие о персонаже, которого никто не видит, не должно
 * выдавать его присутствие звуком или письмом. Единственное исключение — выход из
 * скрытности: он и есть момент, когда персонажа становится видно.
 */
@Component
public class NotificationCandidates {

    private final ChatRepository chats;
    private final ChatMemberRepository channelMembers;
    private final MembershipRepository memberships;
    private final CharacterRepository characters;
    private final PresenceRepository presence;
    private final StorageRepository storages;
    private final GameRepository games;

    public NotificationCandidates(
            ChatRepository chats,
            ChatMemberRepository channelMembers,
            MembershipRepository memberships,
            CharacterRepository characters,
            PresenceRepository presence,
            StorageRepository storages,
            GameRepository games) {
        this.chats = chats;
        this.channelMembers = channelMembers;
        this.memberships = memberships;
        this.characters = characters;
        this.presence = presence;
        this.storages = storages;
        this.games = games;
    }

    public List<NotificationCandidate> forEvent(GameEvent event) {
        return switch (event.type()) {
            case CHAT_MEMBER_INVITED -> whisperInvitation(event);
            case MESSAGE_POSTED -> masterReply(event);
            case TURN_OPENED -> turnOpened(event);
            case CHARACTER_REVEALED -> characterRevealed(event);
            case ITEM_MOVED -> itemOffered(event);
            default -> List.of();
        };
    }

    /**
     * Приглашение в шёпот (`REQUIREMENTS §7.4`). Приглашённый может быть персонажем или
     * участием: у наблюдателя персонажа нет (`D-063`), и приглашение ему тоже адресуется.
     */
    private List<NotificationCandidate> whisperInvitation(GameEvent event) {
        Chat chat = chatOf(event);
        if (chat == null || chat.kind() != ChatKind.WHISPER) {
            return List.of();
        }
        SubjectKind kind = subjectKind(event.payload().get("subjectKind"));
        UUID subjectId = uuidOf(event.payload().get("subjectId"));
        if (kind == null || subjectId == null) {
            return List.of();
        }
        UUID userId = userIdOf(new InboxSubject(kind, subjectId));
        if (userId == null) {
            return List.of();
        }
        return List.of(new NotificationCandidate(
                NotificationEvent.WHISPER_INVITATION,
                userId,
                params(event, chat.id())));
    }

    /**
     * Ответ мастера в личном чате (`D-037`). В этом чате обе стороны пишут `SPEECH`, поэтому
     * автора определяет не вид сообщения, а участие: мастер — тот, кто им является в игре.
     * Адресат — вторая сторона диалога; сам мастер о своей реплике не уведомляется.
     */
    private List<NotificationCandidate> masterReply(GameEvent event) {
        Chat chat = chatOf(event);
        if (chat == null || chat.kind() != ChatKind.MASTER_DM) {
            return List.of();
        }
        UUID masterUserId = games.findById(event.gameId()).map(game -> game.masterUserId()).orElse(null);
        if (masterUserId == null || !masterUserId.equals(event.actorUserId())) {
            return List.of();
        }
        Set<UUID> recipients = new LinkedHashSet<>();
        for (ChatMember member : channelMembers.findJoinedByChat(chat.id())) {
            UUID userId = userIdOf(member.subject());
            if (userId != null && !userId.equals(masterUserId)) {
                recipients.add(userId);
            }
        }
        return candidates(NotificationEvent.MASTER_REPLY, recipients, params(event, chat.id()));
    }

    /**
     * Открытие хода (`REQUIREMENTS §14`). Адресат — игроки: наблюдатель не ходит, и «ваш ход»
     * ему ничего не значит.
     */
    private List<NotificationCandidate> turnOpened(GameEvent event) {
        Set<UUID> recipients = new LinkedHashSet<>();
        for (Membership membership : memberships.findByGame(event.gameId())) {
            if (membership.role() == MembershipRole.PLAYER
                    && membership.status() == MembershipStatus.JOINED) {
                recipients.add(membership.userId());
            }
        }
        return candidates(NotificationEvent.TURN_OPENED, recipients, Map.of(
                "gameId", event.gameId().toString(),
                "eventSeq", event.seq()));
    }

    /**
     * Выход из скрытности (`D-007`): персонажа снова видно, и об этом узнают те, кто стоит
     * рядом. Сам персонаж и его игрок уведомления не получают — они и так знают.
     */
    private List<NotificationCandidate> characterRevealed(GameEvent event) {
        UUID revealed = uuidOf(event.payload().get("characterId"));
        Chat chat = chatOf(event);
        if (revealed == null || chat == null) {
            return List.of();
        }
        Set<UUID> recipients = new LinkedHashSet<>();
        for (Presence placement : presence.findActiveInChat(chat.id())) {
            if (placement.characterId().equals(revealed)) {
                continue;
            }
            UUID userId = occupantOf(placement.characterId());
            if (userId != null) {
                recipients.add(userId);
            }
        }
        Map<String, Object> params = new java.util.LinkedHashMap<>(params(event, chat.id()));
        params.put("characterId", revealed.toString());
        return candidates(NotificationEvent.CHARACTER_REVEALED, recipients, params);
    }

    /**
     * Предложение предмета: предмет оказался в хранилище персонажа (`REQUIREMENTS §10`).
     * Отдельного события у предложения нет — им становится само перемещение, потому что
     * другого факта в журнале не появляется (`D-148`). Перемещение в локационное хранилище
     * адресата не имеет: это не подарок, а обстановка.
     */
    private List<NotificationCandidate> itemOffered(GameEvent event) {
        UUID storageId = uuidOf(event.payload().get("storageId"));
        if (storageId == null) {
            return List.of();
        }
        Storage storage = storages.findById(storageId).orElse(null);
        if (storage == null || !storage.kind().belongsToCharacter() || storage.characterId() == null) {
            return List.of();
        }
        UUID userId = occupantOf(storage.characterId());
        if (userId == null || userId.equals(event.actorUserId())) {
            return List.of();
        }
        Chat chat = chatOf(event);
        Map<String, Object> params = new java.util.LinkedHashMap<>(
                params(event, chat == null ? null : chat.id()));
        params.put("characterId", storage.characterId().toString());
        Object itemId = event.payload().get("newItemId");
        if (itemId == null) {
            itemId = event.payload().get("itemId");
        }
        if (itemId != null) {
            params.put("itemId", itemId.toString());
        }
        return List.of(new NotificationCandidate(NotificationEvent.ITEM_OFFERED, userId, params));
    }

    private List<NotificationCandidate> candidates(
            NotificationEvent type, Set<UUID> recipients, Map<String, Object> params) {
        List<NotificationCandidate> result = new ArrayList<>(recipients.size());
        for (UUID userId : recipients) {
            result.add(new NotificationCandidate(type, userId, params));
        }
        return result;
    }

    /**
     * Чат события. Колонка `game_event.chat_id` заполнена не у всех типов: приглашение
     * в шёпот пишется общим путём и несёт чат только в `payload` — там же его ищет
     * проекция в системные сообщения (`D-031`). Читать только колонку значило бы терять
     * половину событий молча.
     */
    private Chat chatOf(GameEvent event) {
        UUID chatId = event.chatId();
        if (chatId == null) {
            chatId = SystemProjection.chatIdOf(event.payload()).orElse(null);
        }
        return chatId == null ? null : chats.findById(chatId).orElse(null);
    }

    private Map<String, Object> params(GameEvent event, UUID chatId) {
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("gameId", event.gameId().toString());
        params.put("eventSeq", event.seq());
        if (chatId != null) {
            params.put("chatId", chatId.toString());
        }
        return params;
    }

    /** Пользователь за субъектом получения: персонаж → его игрок, участие → человек (`D-086`). */
    private UUID userIdOf(InboxSubject subject) {
        if (subject.kind() == SubjectKind.CHARACTER) {
            return occupantOf(subject.id());
        }
        return memberships.findById(subject.id()).map(Membership::userId).orElse(null);
    }

    private UUID occupantOf(UUID characterId) {
        return characters.findById(characterId)
                .map(Character::occupantMembershipId)
                .map(memberships::findById)
                .flatMap(membership -> membership)
                .map(Membership::userId)
                .orElse(null);
    }

    private static SubjectKind subjectKind(Object value) {
        if (value == null) {
            return null;
        }
        for (SubjectKind kind : SubjectKind.values()) {
            if (kind.name().equals(value.toString())) {
                return kind;
            }
        }
        return null;
    }

    private static UUID uuidOf(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
