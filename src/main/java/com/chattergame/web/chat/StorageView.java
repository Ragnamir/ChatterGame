package com.chattergame.web.chat;

import com.chattergame.item.Storage;
import com.chattergame.item.StorageKind;
import com.chattergame.item.StorageQueryService;

import java.util.List;
import java.util.UUID;

/**
 * Хранилище в ответе API (`REQUIREMENTS §10`). Поле `contentsVisible` отдельно от `items`:
 * клиенту нужно показать сундук, содержимое которого скрыто, иначе «закрытый сундук» и
 * «сундук без предметов» выглядели бы одинаково.
 */
public record StorageView(
        UUID id,
        StorageKind kind,
        UUID chatId,
        UUID characterId,
        String title,
        String description,
        UUID imageAssetId,
        boolean open,
        boolean contentsVisible,
        List<ItemView> items) {

    public static StorageView of(StorageQueryService.VisibleStorage storage) {
        return new StorageView(
                storage.id(),
                storage.kind(),
                storage.chatId(),
                storage.characterId(),
                storage.title(),
                storage.description(),
                storage.imageAssetId(),
                storage.open(),
                storage.contentsVisible(),
                ItemView.of(storage.items()));
    }

    /** Ответ на мутацию: мастер видит хранилище целиком, поэтому фильтр не применяется. */
    public static StorageView of(Storage storage) {
        return new StorageView(
                storage.id(),
                storage.kind(),
                storage.chatId(),
                storage.characterId(),
                storage.title(),
                storage.description(),
                storage.imageAssetId(),
                storage.open(),
                true,
                List.of());
    }

    public static List<StorageView> of(List<StorageQueryService.VisibleStorage> storages) {
        return storages.stream().map(StorageView::of).toList();
    }
}
