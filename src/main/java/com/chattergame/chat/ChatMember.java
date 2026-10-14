package com.chattergame.chat;

import com.chattergame.message.InboxSubject;

import java.time.Instant;
import java.util.UUID;

/**
 * Участник чата (`REQUIREMENTS §7.2`–§7.5). Локации сюда не попадают: там участник — это
 * присутствие, и оно живёт в `presence` вместе с местом персонажа (`D-123`).
 *
 * <p>Субъект участника — пара «вид + идентификатор» (`D-086`), и в шёпоте это существенно:
 * участники — персонажи, а мастер сидит участием, потому что персонажа у него нет. Одна
 * колонка с идентификатором без вида не отличила бы мастера от персонажа.
 *
 * <p>Строка не удаляется: отказ и выход закрывают состояние (`D-010`), поэтому история
 * участия остаётся читаемой, а повторное приглашение после выхода заводит новую строку.
 */
public record ChatMember(
        UUID id,
        UUID chatId,
        InboxSubject subject,
        ChatMemberState state,
        UUID invitedBy,
        Instant createdAt,
        Instant joinedAt,
        Instant leftAt) {

    public static ChatMember invited(UUID id, UUID chatId, InboxSubject subject, UUID invitedBy) {
        return new ChatMember(id, chatId, subject, ChatMemberState.INVITED, invitedBy, Instant.now(), null, null);
    }

    public static ChatMember joined(UUID id, UUID chatId, InboxSubject subject, UUID invitedBy) {
        return new ChatMember(id, chatId, subject, ChatMemberState.JOINED, invitedBy, Instant.now(), Instant.now(), null);
    }

    /** Внутри ли участник прямо сейчас: по этому признаку адресуется лента и пускается запись. */
    public boolean joined() {
        return state == ChatMemberState.JOINED;
    }
}
