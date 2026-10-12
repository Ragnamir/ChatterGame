package com.chattergame.web.media;

import com.chattergame.media.Asset;

import java.time.Instant;
import java.util.UUID;

/**
 * Метаданные ассета в ответе API. Ключа хранилища здесь нет: клиент не должен знать,
 * где лежит файл, — он ходит по `/assets/{id}`, и замена файловой системы на объектное
 * хранилище не меняет контракт (`REQUIREMENTS §19`).
 */
public record AssetView(
        UUID id,
        UUID gameId,
        String mime,
        long byteSize,
        int width,
        int height,
        String sha256,
        String originalName,
        Instant createdAt) {

    public static AssetView of(Asset asset) {
        return new AssetView(
                asset.id(),
                asset.gameId(),
                asset.mime(),
                asset.byteSize(),
                asset.width(),
                asset.height(),
                asset.sha256(),
                asset.originalName(),
                asset.createdAt());
    }
}
