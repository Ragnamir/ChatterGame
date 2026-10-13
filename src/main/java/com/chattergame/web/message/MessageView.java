package com.chattergame.web.message;

import com.chattergame.message.FeedItem;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Строка ленты в ответе: сообщение либо системная проекция журнала (`D-031`). */
public record MessageView(
        long seq,
        String kind,
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
        Instant createdAt) {

    public static MessageView of(FeedItem item) {
        return new MessageView(
                item.seq(),
                item.kind().name(),
                item.messageId(),
                item.chatId(),
                item.characterId(),
                item.authorMembershipId(),
                item.authorUserId(),
                item.onBehalfOfMaster(),
                item.hiddenAuthor(),
                item.body(),
                item.systemCode(),
                item.payload(),
                item.createdAt());
    }
}
