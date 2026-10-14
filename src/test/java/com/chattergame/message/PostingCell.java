package com.chattergame.message;

import com.chattergame.chat.ChatKind;
import com.chattergame.game.Phase;
import com.chattergame.game.MembershipRole;
import com.chattergame.restriction.RestrictionKind;
import com.chattergame.util.ApiException;

import java.util.UUID;

/**
 * Клетка матрицы прав (`REQUIREMENTS §11`) для `E08VisibilityTest`.
 *
 * <p>Отдельный класс, а не вложенный в тест, потому что он модель, а не обвязка:
 * у каждой оси матрицы есть значение по умолчанию (мастер, локация, игра `RUNNING`),
 * и клетка меняет ровно одну ось. Тогда провал адресован конкретному правилу, а не
 * комбинации значений, и таблицу можно читать как таблицу.
 */
public final class PostingCell {

    private static final UUID CHAT = UUID.randomUUID();
    private static final UUID FINAL_CHAT = UUID.randomUUID();
    private static final UUID MASTER_MEMBERSHIP = UUID.randomUUID();
    private static final UUID PLAYER_MEMBERSHIP = UUID.randomUUID();
    private static final UUID MY_CHARACTER = UUID.randomUUID();

    private final MembershipRole role;
    private final ChatKind chatKind;
    private final Phase phase;
    private boolean joined = true;
    private boolean banned;
    private boolean chatArchived;
    private UUID chatId = CHAT;
    private UUID characterId;
    private UUID occupant;
    private boolean present = true;
    private boolean disabled;
    private boolean channelMember;
    private boolean participationMember;
    private boolean archived;
    private java.util.Set<RestrictionKind> onCharacter = java.util.Set.of();
    private java.util.Set<RestrictionKind> onMembership = java.util.Set.of();

    private PostingCell(MembershipRole role, ChatKind chatKind, Phase phase) {
        this.role = role;
        this.chatKind = chatKind;
        this.phase = phase;
        // Клетка закрытого канала по умолчанию описывает его участника: правило «в радиочате
        // только речь» проверяется на том, кто в радиочате состоит, иначе отказ шёл бы не
        // от того правила. Отсутствие участия — отдельная ось, у неё свой метод.
        boolean privateChannel = chatKind == ChatKind.RADIO
                || chatKind == ChatKind.WHISPER
                || chatKind == ChatKind.MASTER_DM;
        this.channelMember = privateChannel && role != MembershipRole.OBSERVER;
        this.participationMember = privateChannel && role != MembershipRole.OBSERVER;
    }

    /** Мастер в указанном типе чата: персонаж не обязателен, он пишет от любого присутствующего. */
    public static PostingCell master(ChatKind kind) {
        return new PostingCell(MembershipRole.MASTER, kind, Phase.RUNNING);
    }

    /** Игрок: по умолчанию его занятый персонаж присутствует. */
    public static PostingCell player(ChatKind kind) {
        return new PostingCell(MembershipRole.PLAYER, kind, Phase.RUNNING)
                .character(MY_CHARACTER, PLAYER_MEMBERSHIP, true);
    }

    /** Наблюдатель: персонажа нет вовсе (`D-063`). */
    public static PostingCell observer(ChatKind kind) {
        return new PostingCell(MembershipRole.OBSERVER, kind, Phase.RUNNING);
    }

    public static UUID myCharacterId() {
        return MY_CHARACTER;
    }

    public PostingCell character(UUID id, UUID occupantMembershipId, boolean inChat) {
        this.characterId = id;
        this.occupant = occupantMembershipId;
        this.present = inChat;
        return this;
    }

    public PostingCell absent() {
        this.present = false;
        return this;
    }

    public PostingCell archived() {
        this.archived = true;
        return this;
    }

    public PostingCell banned() {
        this.banned = true;
        return this;
    }

    public PostingCell notJoined() {
        this.joined = false;
        return this;
    }

    public PostingCell chatArchived() {
        this.chatArchived = true;
        return this;
    }

    public PostingCell phase(Phase next) {
        PostingCell cell = new PostingCell(role, chatKind, next);
        cell.joined = joined;
        cell.banned = banned;
        cell.chatArchived = chatArchived;
        cell.chatId = chatId;
        cell.characterId = characterId;
        cell.occupant = occupant;
        cell.present = present;
        cell.disabled = disabled;
        cell.channelMember = channelMember;
        cell.participationMember = participationMember;
        cell.archived = archived;
        cell.onCharacter = onCharacter;
        cell.onMembership = onMembership;
        return cell;
    }

    /** Участия в закрытом канале нет: `not_a_chat_member` для персонажа и для игрока сразу. */
    public PostingCell notMember() {
        this.channelMember = false;
        this.participationMember = false;
        return this;
    }

    /** Канал отключён мастером (`REQUIREMENTS §7.2`). */
    public PostingCell disabled() {
        this.disabled = true;
        return this;
    }

    public PostingCell atFinalChat() {
        this.chatId = FINAL_CHAT;
        return this;
    }

    public PostingCell characterRestriction(RestrictionKind kind) {
        this.onCharacter = java.util.Set.of(kind);
        return this;
    }

    public PostingCell membershipRestriction(RestrictionKind kind) {
        this.onMembership = java.util.Set.of(kind);
        return this;
    }

    public MessageAccess.Posting build() {
        return new MessageAccess.Posting(
                role,
                joined,
                banned,
                role == MembershipRole.MASTER ? MASTER_MEMBERSHIP : PLAYER_MEMBERSHIP,
                characterId,
                occupant,
                archived,
                chatKind,
                chatId,
                FINAL_CHAT,
                phase,
                chatArchived,
                disabled,
                characterId != null && present,
                channelMember,
                participationMember,
                onCharacter,
                onMembership);
    }

    /** Код отказа или `null`, если клетка разрешающая. */
    public String rejection(MessageKind kind) {
        try {
            MessageAccess.assertCanPost(build(), kind);
            return null;
        } catch (ApiException error) {
            return error.code();
        }
    }
}
