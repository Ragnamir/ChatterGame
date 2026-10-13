package com.chattergame.web.chat;

import com.chattergame.item.ItemSize;
import com.chattergame.item.ItemType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Предмет в запросе мастера (`REQUIREMENTS §10`, `D-114`). Тип и количество приходят вместе
 * с карточкой: предмет без типа не подсказывает ничего мастеру, а количество больше единицы
 * разрешено только исчисляемым типам, и проверка стоит в сервисе, а не в аннотации —
 * она зависит от словаря типов.
 */
public record ItemRequest(
        ItemType type,
        @NotBlank(message = "Укажите название предмета")
        String name,
        String description,
        UUID imageAssetId,
        @NotNull(message = "Укажите размер предмета")
        ItemSize size,
        Integer quantity,
        String properties,
        UUID storageId) {
}
