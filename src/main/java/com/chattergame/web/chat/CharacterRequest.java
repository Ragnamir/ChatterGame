package com.chattergame.web.chat;

import jakarta.validation.constraints.NotBlank;

/**
 * Заведение персонажа мастером. На `E04` доступно ровно то, что нужно для привязки знания
 * ребра (`D-103`): имя. Остальные поля персонажа появятся на `E06`.
 */
public record CharacterRequest(
        @NotBlank(message = "Укажите имя персонажа")
        String name) {
}
