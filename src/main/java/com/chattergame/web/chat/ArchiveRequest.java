package com.chattergame.web.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Причина архивации локации или перехода. Отдельным телом, а не параметром строки запроса:
 * причина — свободный текст игрока, и ей место в теле, где её не нужно кодировать в URL.
 */
public record ArchiveRequest(
        @NotBlank(message = "Укажите причину архивации")
        @Size(max = 500, message = "Причина архивации: до 500 знаков")
        String reason) {
}
