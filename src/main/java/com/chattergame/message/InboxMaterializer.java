package com.chattergame.message;

import com.chattergame.chat.Chat;
import com.chattergame.chat.ChatKind;
import com.chattergame.chat.ChatMember;
import com.chattergame.chat.ChatMemberRepository;
import com.chattergame.chat.ChatRepository;
import com.chattergame.character.Character;
import com.chattergame.character.CharacterRepository;
import com.chattergame.character.Presence;
import com.chattergame.character.PresenceRepository;
import com.chattergame.game.Game;
import com.chattergame.game.GameRepository;
import com.chattergame.game.Membership;
import com.chattergame.game.MembershipRepository;
import com.chattergame.game.MembershipRole;
import com.chattergame.util.Uuids;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Вычисление получателей и материализация видимости (`D-030`, `D-086`).
 *
 * <p>Правило видимости применяется при записи и ровно здесь: любой read-путь читает
 * `inbox_entry`, а кто в него попал — определяется этим классом. Фильтрация на чтении
 * размыла бы правило по всем запросам и дала бы утечки при первом же изменении условий
 * (`D-030`).
 *
 * <p>Субъект получения — персонаж или участие (`D-086`): игровой контент адресуется
 * персонажу, открытый контент — участию наблюдателя, у которого персонажа нет.
 */
@Component
public class InboxMaterializer {

    private final InboxRepository inbox;
    private final PresenceRepository presence;
    private final MembershipRepository memberships;
    private final CharacterRepository characters;
    private final GameRepository games;
    private final ChatRepository chats;
    private final ChatMemberRepository channelMembers;

    public InboxMaterializer(
            InboxRepository inbox,
            PresenceRepository presence,
            MembershipRepository memberships,
            CharacterRepository characters,
            GameRepository games,
            ChatRepository chats,
            ChatMemberRepository channelMembers) {
        this.inbox = inbox;
        this.presence = presence;
        this.memberships = memberships;
        this.characters = characters;
        this.games = games;
        this.chats = chats;
        this.channelMembers = channelMembers;
    }

    /**
     * Записывает строки `inbox_entry` на сообщение.
     *
     * @param requesterUserId спрашивающий: он получает строку всегда, даже если он скрыт —
     *     иначе автор не увидит собственного сообщения в ленте
     * @param characterId персонаж-автор, если сообщение от персонажа
     * @param hiddenAuthor скрытность автора на момент записи (`D-007`): содержимое уходит
     *     только ему самому и мастеру
     * @param addressOccupants адресовать ли участие игрока за присутствующим персонажем.
     *     Нужно шутке: её субъект — участие (`D-099`), поэтому чужую шутку из локации
     *     видит и тот игрок, который читает её не тем персонажем, что стоит в комнате
     */
    public void materializePost(
            Game game,
            UUID chatId,
            long eventSeq,
            UUID messageId,
            UUID requesterUserId,
            UUID characterId,
            boolean hiddenAuthor,
            boolean addressOccupants) {
        Chat chat = chats.findById(chatId).orElse(null);
        ChatKind kind = chat == null ? null : chat.kind();
        LinkedHashSet<InboxSubject> subjects = new LinkedHashSet<>();
        if (characterId != null) {
            subjects.add(InboxSubject.character(characterId));
        }
        Membership requester = memberships.find(game.id(), requesterUserId).orElse(null);
        if (requester != null) {
            subjects.add(InboxSubject.membership(requester.id()));
        }
        if (visibleTo(Viewer.MASTER, kind, hiddenAuthor)) {
            subjects.addAll(masterOnly(game));
        }
        if (visibleTo(Viewer.PRESENT_CHARACTER, kind, hiddenAuthor)) {
            subjects.addAll(participants(game, chat, chatId, addressOccupants));
        }
        if (visibleTo(Viewer.OBSERVER, kind, hiddenAuthor)) {
            subjects.addAll(observers(game));
        }
        insertAll(subjects, chatId, eventSeq, messageId);
    }

    /**
     * Записывает строки `inbox_entry` на системное событие чата (`D-031`): вход и выход,
     * правки, переходы, выдача предмета и запрет видит тот, кто в этом чате находится.
     * Собственной строки в `message` у такого события нет — тело собирается из `payload`
     * при чтении, поэтому `messageId` пуст.
     */
    public void materializeSystem(Game game, UUID chatId, long eventSeq, boolean addressOccupants) {
        Chat chat = chats.findById(chatId).orElse(null);
        insertAll(recipientsOfChat(game, chat, chatId, addressOccupants), chatId, eventSeq, null);
    }

    /** Получатели системного события чата: присутствующие, их игроки, мастер, наблюдатели. */
    private LinkedHashSet<InboxSubject> recipientsOfChat(
            Game game, Chat chat, UUID chatId, boolean addressOccupants) {
        ChatKind kind = chat == null ? null : chat.kind();
        LinkedHashSet<InboxSubject> subjects = new LinkedHashSet<>();
        if (visibleTo(Viewer.MASTER, kind, false)) {
            subjects.addAll(masterOnly(game));
        }
        if (visibleTo(Viewer.PRESENT_CHARACTER, kind, false)) {
            subjects.addAll(participants(game, chat, chatId, addressOccupants));
        }
        if (visibleTo(Viewer.OBSERVER, kind, false)) {
            subjects.addAll(observers(game));
        }
        return subjects;
    }

    /**
     * Кто «внутри» чата (`REQUIREMENTS §7.1`–§7.5). Для локации это присутствие, для
     * радиочата, шёпота, личного чата и финального обсуждения — участие в `chat_member`
     * (`D-123`): у локации участник — тот, кто стоит в ней, у закрытого канала — тот, кого
     * в него позвали, и это разные состояния, которые нельзя выразить одним.
     */
    private LinkedHashSet<InboxSubject> participants(
            Game game, Chat chat, UUID chatId, boolean addressOccupants) {
        if (chat != null && chat.kind() != ChatKind.LOCATION) {
            return channelParticipants(game, chatId, addressOccupants);
        }
        return presentInChat(game, chatId, addressOccupants);
    }

    /** Участники закрытого канала: их держит `chat_member`, а не присутствие в локации. */
    private LinkedHashSet<InboxSubject> channelParticipants(
            Game game, UUID chatId, boolean addressOccupants) {
        LinkedHashSet<InboxSubject> subjects = new LinkedHashSet<>();
        Map<UUID, Character> occupied = characters.findByGame(game.id()).stream()
                .filter(character -> character.occupantMembershipId() != null)
                .collect(Collectors.toMap(
                        Character::id, Function.identity(), (first, second) -> first));
        for (ChatMember member : channelMembers.findJoinedByChat(chatId)) {
            subjects.add(member.subject());
            if (addressOccupants && member.subject().kind() == SubjectKind.CHARACTER) {
                Character character = occupied.get(member.subject().id());
                if (character != null) {
                    subjects.add(InboxSubject.membership(character.occupantMembershipId()));
                }
            }
        }
        return subjects;
    }

    /** Персонажи, стоящие в чате, и — если просили — участия их игроков. */
    private LinkedHashSet<InboxSubject> presentInChat(Game game, UUID chatId, boolean addressOccupants) {
        LinkedHashSet<InboxSubject> subjects = new LinkedHashSet<>();
        Map<UUID, Character> occupied = characters.findByGame(game.id()).stream()
                .filter(character -> character.occupantMembershipId() != null)
                .collect(Collectors.toMap(
                        Character::id, Function.identity(), (first, second) -> first));
        for (Presence placement : presence.findActiveInChat(chatId)) {
            subjects.add(InboxSubject.character(placement.characterId()));
            if (addressOccupants) {
                Character character = occupied.get(placement.characterId());
                if (character != null) {
                    subjects.add(InboxSubject.membership(character.occupantMembershipId()));
                }
            }
        }
        return subjects;
    }

    private LinkedHashSet<InboxSubject> observers(Game game) {
        LinkedHashSet<InboxSubject> subjects = new LinkedHashSet<>();
        for (Membership membership : memberships.findByGame(game.id())) {
            if (membership.role() == MembershipRole.OBSERVER && membership.joined()) {
                subjects.add(InboxSubject.membership(membership.id()));
            }
        }
        return subjects;
    }

    private List<InboxSubject> masterOnly(Game game) {
        return memberships.find(game.id(), game.masterUserId())
                .map(master -> List.of(InboxSubject.membership(master.id())))
                .orElse(List.of());
    }

    private void insertAll(LinkedHashSet<InboxSubject> subjects, UUID chatId, long eventSeq, UUID messageId) {
        Instant now = Instant.now();
        for (InboxSubject subject : subjects) {
            inbox.insert(new InboxEntry(Uuids.v7(), subject, chatId, eventSeq, messageId, now));
        }
    }

    /**
     * Материализация без вычисления получателей: строка уже существующего события
     * дописывается конкретному субъекту. Нужна входу наблюдателя в середину игры, где
     * исторических событий много и вычислять по каждому получателей незачем (`D-086`).
     */
    public void backfill(UUID chatId, long eventSeq, UUID messageId, InboxSubject subject) {
        inbox.insert(new InboxEntry(Uuids.v7(), subject, chatId, eventSeq, messageId, Instant.now()));
    }

    /** Открытый контент — то, что видит наблюдатель: локации и финальное обсуждение (`D-063`). */
    public static boolean openToObservers(ChatKind kind) {
        return kind == ChatKind.LOCATION || kind == ChatKind.FINAL;
    }

    /**
     * Кто получает строку `inbox_entry` — матрица видимости одним предикатом:
     * скрытность автора × роль смотрящего × тип чата (`REQUIREMENTS §7.1`). Вынесена из
     * {@link #recipientsOfChat} затем, чтобы её можно было проверить таблицей целиком,
     * включая отрицательные клетки, без базы: правило — это функция от трёх значений,
     * а не последовательность запросов.
     *
     * <p>Скрытый автор — единственный случай, когда чат вообще не участвует: содержимое
     * уходит автору и мастеру, и точка. Иначе открытый контент достаётся и наблюдателю,
     * а закрытый — только тем, кто внутри.
     */
    public static boolean visibleTo(Viewer viewer, ChatKind kind, boolean hiddenAuthor) {
        return switch (viewer) {
            case AUTHOR, REQUESTER, MASTER -> true;
            case PRESENT_CHARACTER, PRESENT_OCCUPANT -> !hiddenAuthor;
            case OBSERVER -> !hiddenAuthor && openToObservers(kind);
        };
    }

    /**
     * Роль смотрящего в матрице видимости. Названа действием, а не состоянием: клетка
     * отвечает на вопрос «получает ли строку тот, кто…», и лишних осей здесь нет —
     * пошаговый режим на доставку не влияет (`REQUIREMENTS §14`: там лимиты, не видимость).
     */
    public enum Viewer {
        /** Персонаж-автор сообщения или участия-автора: своего автора видит всегда. */
        AUTHOR,
        /** Спрашивающий, чьим действием идёт запись: без него автор не увидел бы свою строку. */
        REQUESTER,
        /** Мастер игры: видит всё, включая скрытое (`D-007`). */
        MASTER,
        /** Персонаж, стоящий в этом чате. */
        PRESENT_CHARACTER,
        /** Участие игрока, стоящего в этом чате своим персонажем. */
        PRESENT_OCCUPANT,
        /** Участие-наблюдатель: открытый контент, пока автор не скрыт (`D-063`). */
        OBSERVER
    }
}
