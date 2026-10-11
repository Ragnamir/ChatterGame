package com.chattergame.game;

import java.util.function.Predicate;

/**
 * Разрешённые переходы состояния игры (`REQUIREMENTS §4`).
 *
 * <p>Правило — данные в таблице, а не ветвление по коду: табличный тест обходит эту таблицу
 * целиком, поэтому новый переход не может появиться в обход теста, а запрет не может
 * потеряться при рефакторинге. Причина отказа формулируется для мастера, а не для лога:
 * `REQUIREMENTS §4` прямо требует внятный текст, поэтому проверка живёт здесь, а не в
 * БД-чек-констрейнте.
 */
public enum GameTransition {

    /** `DRAFT → READY`. Готовность с предупреждениями допустима (`D-009`). */
    MARK_READY(
            "readiness_already_ready",
            "Игра уже объявлена готовой",
            state -> state.readiness() == Readiness.READY),

    /** `READY → DRAFT` возможен только до старта: сыгранную игру в черновик не вернуть. */
    RETURN_TO_DRAFT(
            "readiness_rollback_after_start",
            "Откат к черновику возможен только до старта игры",
            state -> state.readiness() != Readiness.READY || state.phase() != Phase.NOT_STARTED),

    /** `NOT_STARTED → RUNNING`. Необратимо (`REQUIREMENTS §4`). */
    START(
            "start_not_allowed",
            "Старт возможен только у готовой игры, которая ещё не начата",
            state -> state.phase() != Phase.NOT_STARTED || state.readiness() != Readiness.READY),

    /** `RUNNING → FINISHED`. Необратимо; после этого мир только читается (`D-094`). */
    FINISH(
            "finish_not_allowed",
            "Завершить можно только идущую игру",
            state -> state.phase() != Phase.RUNNING),

    /** Пауза. Устанавливается в любой фазе, кроме `FINISHED`, и требует причину (`D-059`). */
    PAUSE(
            "pause_not_allowed",
            "Поставить на паузу можно только незавершённую игру",
            state -> state.phase() == Phase.FINISHED || state.paused()),

    /** Снятие паузы. После `FINISHED` теряет смысл: игра и так read-only (`D-094`). */
    RESUME(
            "resume_not_allowed",
            "Игра не на паузе или уже завершена",
            state -> state.phase() == Phase.FINISHED || !state.paused());

    private final String code;
    private final String reason;
    private final Predicate<GameState> denied;

    GameTransition(String code, String reason, Predicate<GameState> denied) {
        this.code = code;
        this.reason = reason;
        this.denied = denied;
    }

    public boolean allowedFrom(GameState state) {
        return !denied.test(state);
    }

    public String code() {
        return code;
    }

    public String reason() {
        return reason;
    }
}
