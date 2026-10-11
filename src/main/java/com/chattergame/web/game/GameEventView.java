package com.chattergame.web.game;

import com.chattergame.journal.GameEvent;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Событие журнала в аудит-чтении (`PLAN` E03, задача 7). Отдаётся как есть, без сокрытия полей. */
public record GameEventView(
        UUID id,
        long seq,
        String type,
        UUID chatId,
        UUID actorUserId,
        UUID characterId,
        boolean onBehalfOfMaster,
        Map<String, Object> payload,
        Instant createdAt) {

    public static GameEventView of(GameEvent event) {
        return new GameEventView(
                event.id(),
                event.seq(),
                event.type().name(),
                event.chatId(),
                event.actorUserId(),
                event.characterId(),
                event.onBehalfOfMaster(),
                event.payload(),
                event.createdAt());
    }
}
