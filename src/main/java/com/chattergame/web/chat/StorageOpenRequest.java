package com.chattergame.web.chat;

import jakarta.validation.constraints.NotNull;

/**
 * Открытие и закрытие хранилища (`D-110`). Одно поле вместо двух маршрутов: в журнале
 * событие тоже одно, с признаком в `payload`.
 */
public record StorageOpenRequest(
        @NotNull(message = "Укажите, открыто хранилище или нет")
        Boolean open) {
}
