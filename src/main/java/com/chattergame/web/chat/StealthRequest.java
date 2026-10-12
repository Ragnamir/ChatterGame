package com.chattergame.web.chat;

import jakarta.validation.constraints.NotNull;

/**
 * Скрытность персонажа (`D-006`): `hidden = true` — вход, `false` — выход. Причина свободным
 * текстом попадает в событие: игроку, который видит «ушёл», мастер должен быть в состоянии
 * объяснить, почему.
 */
public record StealthRequest(
        @NotNull(message = "Укажите, скрыт персонаж или нет")
        Boolean hidden,
        String reason) {
}
