package com.chattergame.web.chat;

import com.chattergame.item.Item;
import com.chattergame.item.ItemType;
import com.chattergame.item.StorageQueryService;

import java.util.List;
import java.util.UUID;

/**
 * Предмет в ответе API. Отдельно от {@link Item} записи: наружу уходит материализованное
 * правило видимости (`D-019`), а не строка таблицы, и читается это правило в одном месте —
 * {@link StorageQueryService}.
 */
public record ItemView(
        UUID id,
        ItemType type,
        String name,
        String description,
        UUID imageAssetId,
        String size,
        int quantity,
        String properties) {

    public static ItemView of(Item item) {
        return new ItemView(
                item.id(),
                item.type(),
                item.name(),
                item.description(),
                item.imageAssetId(),
                item.size().name(),
                item.quantity(),
                item.properties());
    }

    public static ItemView of(StorageQueryService.VisibleItem item) {
        return new ItemView(
                item.id(),
                item.type(),
                item.name(),
                item.description(),
                item.imageAssetId(),
                item.size().name(),
                item.quantity(),
                item.properties());
    }

    public static List<ItemView> of(List<StorageQueryService.VisibleItem> items) {
        return items.stream().map(ItemView::of).toList();
    }
}
