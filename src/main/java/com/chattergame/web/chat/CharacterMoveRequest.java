package com.chattergame.web.chat;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Перемещение своим персонажем по переходу (`REQUIREMENTS §8`, `PLAN` E13). В теле только
 * переход: направление берётся у ребра, а не у клиента, иначе игрок указал бы «откуда»,
 * и проверка «вы стоите именно там» обходилась бы.
 */
public record CharacterMoveRequest(
        @NotNull(message = "Укажите переход")
        UUID transitionId) {
}
