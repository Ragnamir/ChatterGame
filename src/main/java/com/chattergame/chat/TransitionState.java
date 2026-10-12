package com.chattergame.chat;

import java.util.UUID;

/**
 * Знание ребра конкретным персонажем (`REQUIREMENTS §8`). Уникально по
 * `(transition, character)`: у персонажа одно состояние на ребро, иначе клиент получил бы
 * два противоречащих ответа и выбрал бы более выгодный.
 *
 * <p>{@code reason} осмыслен у {@link EdgeState#KNOWN_UNAVAILABLE} — там он показывается
 * игроку. У остальных состояний он может быть пустым.
 */
public record TransitionState(
        UUID id,
        UUID transitionId,
        UUID characterId,
        EdgeState state,
        String reason) {
}
