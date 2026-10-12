package com.chattergame.media;

import java.time.Instant;
import java.util.UUID;

/**
 * Ассет (`REQUIREMENTS §19`). Хранит и метаданные, и ключ содержимого; сам файл лежит
 * в {@link AssetStore}.
 *
 * <p>`width` и `height` записаны потому, что клиенту нужны пропорции до загрузки картинки:
 * без них список локаций дёргается при появлении каждой миниатюры.
 */
public record Asset(
        UUID id,
        UUID gameId,
        UUID ownerUserId,
        String mime,
        long byteSize,
        int width,
        int height,
        String sha256,
        String originalName,
        String storageKey,
        Instant createdAt) {
}
