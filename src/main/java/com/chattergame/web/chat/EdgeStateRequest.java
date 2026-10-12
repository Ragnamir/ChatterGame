package com.chattergame.web.chat;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Установка знания ребра конкретным персонажем (`REQUIREMENTS §8`). `reason` обязателен при
 * `KNOWN_UNAVAILABLE`: именно текст показывается игроку вместо кнопки перехода.
 */
public record EdgeStateRequest(
        @NotNull(message = "Укажите персонажа")
        UUID characterId,

        @NotNull(message = "Укажите состояние перехода")
        String state,

        String reason) {
}
