package com.chattergame.item;

import java.util.UUID;

/**
 * Размещение предмета (`REQUIREMENTS §10`). У предмета ровно одна строка: уникальность
 * `item_placement(item_id)` и есть инвариант «предмета вне хранилища не бывает».
 *
 * <p>Перемещение — это `UPDATE` строки, а не удаление с вставкой: физического удаления
 * доменных строк нет (`D-010`), а история перемещений живёт в `game_event`, потому что
 * журнал и есть единственный источник истины (`D-033`).
 */
public record ItemPlacement(
        UUID id,
        UUID itemId,
        UUID storageId) {
}
