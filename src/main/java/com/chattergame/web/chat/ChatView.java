package com.chattergame.web.chat;

import com.chattergame.chat.Chat;

import java.time.Instant;
import java.util.UUID;

/**
 * Локация в ответе API. `archived` отделён от `archivedReason` намеренно: клиенту нужно
 * различать «место ещё в игре» и «выведено, потому что…» (`D-009`), и одно булево поле
 * заставило бы его переспрашивать причину отдельным запросом.
 */
public record ChatView(
        UUID id,
        UUID gameId,
        String kind,
        String title,
        String description,
        UUID imageAssetId,
        boolean archived,
        String archivedReason,
        Instant archivedAt,
        Instant createdAt) {

    public static ChatView of(Chat chat) {
        return new ChatView(
                chat.id(),
                chat.gameId(),
                chat.kind().name(),
                chat.title(),
                chat.description(),
                chat.imageAssetId(),
                chat.archived(),
                chat.archivedReason(),
                chat.archivedAt(),
                chat.createdAt());
    }
}
