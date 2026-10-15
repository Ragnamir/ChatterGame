package com.chattergame.message;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Элемент ленты чата: либо сообщение, либо системная проекция журнала (`D-031`).
 *
 * <p>Один тип на оба случая, потому что порядок у них общий — номер события (`D-033`),
 * и разделять ленту на два списка значило бы заставлять клиента склеивать её самому.
 * У системной строки пусты `messageId` и `body`, зато заполнен `systemCode`: текст
 * переводится клиентом по ключу интернализации, а не приходит с сервера (`D-081`).
 *
 * <p>`matchedNoteIds` — заметки, подсвечивающие эту строку (`D-050`). Список приходит
 * готовым, а не считается на чтении, и клиент лишь фильтрует его по живым заметкам и
 * своим правам (`D-052`, `D-087`).
 */
public record FeedItem(
        long seq,
        MessageKind kind,
        UUID messageId,
        UUID chatId,
        UUID characterId,
        UUID authorMembershipId,
        UUID authorUserId,
        boolean onBehalfOfMaster,
        boolean hiddenAuthor,
        String body,
        String systemCode,
        Map<String, Object> payload,
        Instant createdAt,
        java.util.List<UUID> matchedNoteIds) {

    public static FeedItem of(Message message) {
        Object hidden = message.payload() == null ? null : message.payload().get("hiddenAuthor");
        return new FeedItem(
                message.seq(),
                message.kind(),
                message.id(),
                message.chatId(),
                message.characterId(),
                message.authorMembershipId(),
                message.authorUserId(),
                message.onBehalfOfMaster(),
                Boolean.TRUE.equals(hidden) || "true".equals(String.valueOf(hidden)),
                message.body(),
                null,
                message.payload(),
                message.createdAt(),
                message.matchedNoteIds());
    }

    public static FeedItem system(long seq, UUID chatId, String code, Map<String, Object> payload, Instant at) {
        return new FeedItem(
                seq, MessageKind.SYSTEM, null, chatId, null, null, null, false, false,
                null, code, payload, at, java.util.List.of());
    }
}
