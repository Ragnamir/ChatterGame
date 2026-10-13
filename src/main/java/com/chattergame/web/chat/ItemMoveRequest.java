package com.chattergame.web.chat;

import java.util.UUID;

/**
 * Перемещение предмета (`D-015`). `fromStorageId` — ожидаемое текущее хранилище: именно
 * условная проверка источника отвечает «предмет уже забран» тому, кто проиграл гонку.
 * `null` означает «откуда угодно» и доступен мастеру. `quantity` меньше текущего количества
 * отделяет часть стопки (`D-114`).
 */
public record ItemMoveRequest(
        UUID fromStorageId,
        UUID storageId,
        Integer quantity) {
}
