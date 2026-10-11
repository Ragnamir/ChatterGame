package com.chattergame.game;

import com.chattergame.util.ApiException;

/**
 * Применение перехода к состоянию. Класс чистый: ни БД, ни времени, ни прав — только оси
 * (`REQUIREMENTS §4`). Право мастера проверяет вызывающий слой, а здесь остаётся то, что
 * можно проверить табличным тестом без базы.
 */
public final class GameStateMachine {

    private GameStateMachine() {
    }

    /**
     * `pauseReason` обязателен только для {@link GameTransition#PAUSE}: пауза без причины
     * выглядит для игрока как зависший мастер (`D-059`).
     */
    public static GameState apply(GameState current, GameTransition transition, String pauseReason) {
        if (!transition.allowedFrom(current)) {
            throw ApiException.conflict(transition.code(), transition.reason());
        }
        return switch (transition) {
            case MARK_READY -> with(current, Readiness.READY, current.phase(), current.paused(), current.pauseReason());
            case RETURN_TO_DRAFT -> with(current, Readiness.DRAFT, current.phase(), current.paused(), current.pauseReason());
            case START -> with(current, current.readiness(), Phase.RUNNING, current.paused(), current.pauseReason());
            // Завершение снимает паузу: снять её после FINISHED уже нельзя (D-094).
            case FINISH -> with(current, current.readiness(), Phase.FINISHED, false, null);
            case PAUSE -> with(current, current.readiness(), current.phase(), true, requireReason(pauseReason));
            case RESUME -> with(current, current.readiness(), current.phase(), false, null);
        };
    }

    private static String requireReason(String pauseReason) {
        String reason = pauseReason == null ? "" : pauseReason.strip();
        if (reason.isEmpty()) {
            throw ApiException.badRequest("pause_reason_required", "Укажите причину паузы");
        }
        return reason;
    }

    private static GameState with(
            GameState current, Readiness readiness, Phase phase, boolean paused, String pauseReason) {
        return new GameState(
                current.gameId(), readiness, current.visibility(), phase, paused, pauseReason, current.archived());
    }
}
