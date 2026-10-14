package com.chattergame.web.chat;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Создание шёпота (`REQUIREMENTS §7.4`, `D-038`). Персонажи, которыми начинают разговор:
 * инициатор входит сразу, остальным уходит приглашение, и они входят по согласию. Отдельного
 * поля «названия» нет: у шёпота его нет, а участники служат его подписью в списке чатов.
 */
public record WhisperRequest(
        @NotNull(message = "Укажите собеседников")
        List<UUID> characterIds) {
}
