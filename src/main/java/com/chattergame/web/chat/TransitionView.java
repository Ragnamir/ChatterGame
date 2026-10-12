package com.chattergame.web.chat;

import com.chattergame.chat.Transition;

import java.time.Instant;
import java.util.UUID;

/**
 * Переход в ответе мастеру. Состояние знания персонажа здесь нет: у ребра его не существует,
 * оно есть только в паре «ребро + персонаж» (`REQUIREMENTS §8`), и отдаётся графом.
 */
public record TransitionView(
        UUID id,
        UUID gameId,
        UUID fromChatId,
        UUID toChatId,
        String title,
        boolean archived,
        String archivedReason,
        Instant archivedAt,
        Instant createdAt) {

    public static TransitionView of(Transition transition) {
        return new TransitionView(
                transition.id(),
                transition.gameId(),
                transition.fromChatId(),
                transition.toChatId(),
                transition.title(),
                transition.archived(),
                transition.archivedReason(),
                transition.archivedAt(),
                transition.createdAt());
    }
}
