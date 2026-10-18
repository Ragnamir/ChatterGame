package com.chattergame.web.game;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Причина архивации игры. Причина обязательна (`D-010`): архив без причины не объясняет
 * участникам, почему игра перестала идти, а физического удаления в проекте нет вовсе.
 */
public record ArchiveGameRequest(
        @NotBlank(message = "Укажите причину архивации")
        @Size(max = 500, message = "Причина архивации: до 500 знаков")
        String reason) {
}
