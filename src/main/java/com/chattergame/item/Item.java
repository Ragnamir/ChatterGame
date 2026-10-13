package com.chattergame.item;

import java.time.Instant;
import java.util.UUID;

/**
 * Предмет (`REQUIREMENTS §10`, `D-114`). Тип и количество добавлены `E07`: тип задаёт
 * подсказку мастеру и признак исчисляемости, количество позволяет складывать в стопку
 * деньги и патроны.
 *
 * <p>`quantity` — целое, не меньше единицы, и это держит ограничение базы: строка с нулём не
 * должна появиться даже при ошибке в коде. Правило «у неисчисляемого типа всегда `1`»
 * проверяет сервис — словарь типов обязан расширяться без новой миграции (`D-114`).
 *
 * <p>`properties` — произвольные текстовые свойства мастера. Это не механические
 * характеристики: игровых механик в проекте нет (`D-011`).
 */
public record Item(
        UUID id,
        UUID gameId,
        ItemType type,
        String name,
        String description,
        UUID imageAssetId,
        ItemSize size,
        int quantity,
        String properties,
        Instant createdAt,
        Instant updatedAt) {

    /** Максимум колонки `item.name`; проверяется на сервисе ради текста ошибки. */
    public static final int NAME_MAX = 200;
}
