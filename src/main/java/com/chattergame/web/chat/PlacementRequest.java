package com.chattergame.web.chat;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Размещение персонажа в локации (`PLAN` E06, задача 3). Стартовое и последующее — один вызов. */
public record PlacementRequest(
        @NotNull(message = "Укажите локацию")
        UUID chatId) {
}
