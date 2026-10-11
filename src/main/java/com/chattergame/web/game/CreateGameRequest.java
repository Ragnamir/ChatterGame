package com.chattergame.web.game;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Создание игры мастером. Видимость не запрашивается: значение по умолчанию — `PRIVATE` из `001-baseline.yaml`. */
public record CreateGameRequest(
        @NotBlank @Size(max = 200) String title,
        @Size(max = 20000) String publicDescription,
        @Size(max = 20000) String privateDescription) {
}
