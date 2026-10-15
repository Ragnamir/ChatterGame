package com.chattergame.chat;

import com.chattergame.game.Game;
import com.chattergame.game.GameQueryService;
import com.chattergame.game.Membership;
import com.chattergame.game.MembershipRepository;
import com.chattergame.game.MembershipRole;
import com.chattergame.message.InboxMaterializer;
import com.chattergame.message.InboxRepository;
import com.chattergame.message.InboxSubject;
import com.chattergame.message.SubjectKind;
import com.chattergame.util.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Список закрытых каналов игры глазами смотрящего (`PLAN` E11, задача 5).
 *
 * <p>Список строится по участию в `chat_member`, а не по ленте: `D-030` запрещает отбор
 * строк сообщений на чтении, но не отбор каналов. Разница существенная — здесь нет ни
 * одного сообщения, только шапка канала и счётчик непрочитанного, а счётчик берётся из
 * `inbox_entry` (`D-036`), то есть из того же материала.
 *
 * <p>Наблюдатель в личные каналы не допускается вовсе (`D-063`). Мастер видит все каналы
 * игры, потому что ведёт её, но не видит чужих личных чатов, где он не участник (`D-037`).
 */
@Service
public class ChannelQueryService {

    private final ChatRepository chats;
    private final ChatMemberRepository members;
    private final MembershipRepository memberships;
    private final InboxRepository inbox;
    private final InboxMaterializer visibility;
    private final GameQueryService games;

    public ChannelQueryService(
            ChatRepository chats,
            ChatMemberRepository members,
            MembershipRepository memberships,
            InboxRepository inbox,
            InboxMaterializer visibility,
            GameQueryService games) {
        this.chats = chats;
        this.members = members;
        this.memberships = memberships;
        this.inbox = inbox;
        this.visibility = visibility;
        this.games = games;
    }

    /** Каналы игры, видимые смотрящему, вместе с его состоянием в каждом и счётчиком. */
    @Transactional(readOnly = true)
    public List<ChannelAccess> channels(UUID userId, UUID gameId) {
        Game game = games.requireReadable(userId, gameId);
        Membership membership = membershipOf(gameId, userId);
        List<ChannelAccess> result = new ArrayList<>();
        for (Chat chat : chats.findByGame(game.id())) {
            if (chat.kind() != ChatKind.LOCATION) {
                ChannelAccess access = access(chat, membership);
                if (access != null) {
                    result.add(access);
                }
            }
        }
        return result;
    }

    /** Канал с проверкой, что он есть в игре и виден смотрящему. */
    @Transactional(readOnly = true)
    public ChannelAccess requireVisible(UUID userId, UUID gameId, UUID chatId) {
        games.requireReadable(userId, gameId);
        Membership membership = membershipOf(gameId, userId);
        Chat chat = chats.findById(chatId)
                .orElseThrow(() -> ApiException.notFound("chat_not_found", "Чат не найден"));
        if (!chat.gameId().equals(gameId) || chat.kind() == ChatKind.LOCATION) {
            throw ApiException.notFound("chat_not_found", "Чат не найден");
        }
        ChannelAccess access = access(chat, membership);
        if (access == null) {
            throw ApiException.forbidden("not_a_chat_member", "Этот канал вам не виден");
        }
        return access;
    }

    /** Участники канала: приглашённые и вошедшие, в порядке приглашения (`REQUIREMENTS §7.4`). */
    @Transactional(readOnly = true)
    public List<ChatMember> participants(UUID gameId, UUID chatId) {
        return members.findActiveByChat(chatId);
    }

    /**
     * Доступ смотрящего к каналу или `null`, если канал ему не виден. Субъект участия —
     * персонаж, но у мастера и наблюдателя персонажа нет, поэтому «мои субъекты» — это
     * персонажи, которыми я играю, плюс моё собственное участие (`D-086`).
     */
    private ChannelAccess access(Chat chat, Membership membership) {
        if (membership.role() == MembershipRole.OBSERVER) {
            // Наблюдателю закрытые каналы не видны (`D-063`), но финальное обсуждение —
            // открытый чат (`InboxMaterializer.openToObservers`), и шутить в нём ему можно
            // (`REQUIREMENTS §11`). Отказ был бы отказом в чате, который ему читают.
            // Локация открывается не типом, а историей: место, куда никто не входил,
            // наблюдателю не показывается (`D-129`).
            if (!visibility.openToObserver(chat.kind(), chat.id())) {
                return null;
            }
            InboxSubject subject = InboxSubject.membership(membership.id());
            return new ChannelAccess(chat, null, false, inbox.unreadIn(subject, chat.id()));
        }
        List<ChatMember> active = members.findActiveByChat(chat.id());
        ChatMember mine = null;
        boolean myself = false;
        for (ChatMember member : active) {
            if (member.subject().kind() == SubjectKind.MEMBERSHIP
                    && member.subject().id().equals(membership.id())) {
                mine = member;
                myself = true;
                break;
            }
        }
        if (mine == null && membership.role() != MembershipRole.MASTER) {
            // Участие игрока держится персонажем, а не участием (`REQUIREMENTS §7.4`):
            // искать его нужно по занятости персонажа, а не по одному идентификатору.
            for (ChatMember member : active) {
                if (member.subject().kind() == SubjectKind.CHARACTER
                        && owns(membership, member.subject().id())) {
                    mine = member;
                    break;
                }
            }
        }
        boolean master = membership.role() == MembershipRole.MASTER;
        if (mine == null && !master) {
            return null;
        }
        if (chat.kind() == ChatKind.MASTER_DM && !myself) {
            return null;
        }
        InboxSubject subject = mine != null ? mine.subject() : InboxSubject.membership(membership.id());
        return new ChannelAccess(chat, mine, master, inbox.unreadIn(subject, chat.id()));
    }

    private boolean owns(Membership membership, UUID characterId) {
        return members.ownsCharacter(membership.id(), characterId);
    }

    private Membership membershipOf(UUID gameId, UUID userId) {
        return memberships.find(gameId, userId)
                .orElseThrow(() -> ApiException.forbidden("not_a_member", "Нет доступа к этой игре"));
    }

    /** Канал и место смотрящего в нём: `member` пуст у мастера, который в канал не входил. */
    public record ChannelAccess(Chat chat, ChatMember member, boolean master, long unread) {

        public boolean invited() {
            return member != null && member.state() == ChatMemberState.INVITED;
        }
    }
}
