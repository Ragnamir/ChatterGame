package com.chattergame.web.chat;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Создание ориентированного перехода. Название необязательно: у ребра может не быть
 * игрового имени, и тогда клиент показывает название места назначения.
 */
public record TransitionRequest(
        @NotNull(message = "Укажите исходную локацию")
        UUID fromChatId,

        @NotNull(message = "Укажите целевую локацию")
        UUID toChatId,

        String title) {
}
