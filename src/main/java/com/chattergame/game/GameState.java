package com.chattergame.game;

import java.util.UUID;

/**
 * Состояние игры как единое значение: три независимые оси плюс пауза (`D-003`).
 *
 * <p>`pauseReason` хранится рядом с флагом, а не отдельно: игрок видит именно причину
 * ожидания (`D-059`), а флаг без причины превращает паузу в молчание мастера.
 */
public record GameState(
        UUID gameId,
        Readiness readiness,
        Visibility visibility,
        Phase phase,
        boolean paused,
        String pauseReason,
        boolean archived) {

    public boolean readOnly() {
        return phase == Phase.FINISHED;
    }
}
