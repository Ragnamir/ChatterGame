package com.chattergame.game;

import com.chattergame.util.ApiException;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Табличная проверка state machine (`REQUIREMENTS §4`, `D-003`). Тест обходит таблицу
 * переходов целиком и требует случай на каждый переход: переход, добавленный в
 * {@link GameTransition} и не описанный здесь, валит тест, а не проезжает молча.
 */
class E03StateMachineTest {

    private static final UUID GAME = UUID.fromString("01920000-0000-7000-8000-000000000001");

    private record Case(GameState from, boolean allowed) {
    }

    private static GameState state(Readiness readiness, Phase phase, boolean paused, String pauseReason) {
        return new GameState(GAME, readiness, Visibility.PRIVATE, phase, paused, pauseReason, false);
    }

    private static GameState draft() {
        return state(Readiness.DRAFT, Phase.NOT_STARTED, false, null);
    }

    private static GameState ready() {
        return state(Readiness.READY, Phase.NOT_STARTED, false, null);
    }

    private static GameState running() {
        return state(Readiness.READY, Phase.RUNNING, false, null);
    }

    private static GameState paused() {
        return state(Readiness.READY, Phase.RUNNING, true, "мастер ушёл на два дня");
    }

    private static GameState finished() {
        return state(Readiness.READY, Phase.FINISHED, false, null);
    }

    private static GameState readyWithPhase(Phase phase) {
        return state(Readiness.READY, phase, false, null);
    }

    private static final Map<GameTransition, List<Case>> TABLE = new EnumMap<>(GameTransition.class);

    static {
        TABLE.put(GameTransition.MARK_READY, List.of(
                new Case(draft(), true),
                new Case(ready(), false),
                new Case(running(), false),
                new Case(finished(), false)));

        TABLE.put(GameTransition.RETURN_TO_DRAFT, List.of(
                new Case(ready(), true),
                // Откат запрещён ровно потому, что игра уже началась: сыгранное не отменить.
                new Case(readyWithPhase(Phase.RUNNING), false),
                new Case(readyWithPhase(Phase.FINISHED), false),
                new Case(draft(), false)));

        TABLE.put(GameTransition.START, List.of(
                new Case(ready(), true),
                new Case(draft(), false),
                new Case(running(), false),
                new Case(finished(), false)));

        TABLE.put(GameTransition.FINISH, List.of(
                new Case(running(), true),
                new Case(paused(), true),
                new Case(draft(), false),
                new Case(ready(), false),
                new Case(finished(), false)));

        TABLE.put(GameTransition.PAUSE, List.of(
                new Case(running(), true),
                // Пауза допустима до старта: мастер готовит мир и уходит на день.
                new Case(draft(), true),
                new Case(ready(), true),
                new Case(paused(), false),
                new Case(finished(), false)));

        TABLE.put(GameTransition.RESUME, List.of(
                new Case(paused(), true),
                new Case(running(), false),
                new Case(draft(), false),
                new Case(finished(), false)));
    }

    /** Табличный обход: каждая ось проверяется и на разрешение, и на отказ. */
    @Test
    void everyTransitionIsAllowedOrRefusedAsTheTableSays() {
        for (Map.Entry<GameTransition, List<Case>> entry : TABLE.entrySet()) {
            GameTransition transition = entry.getKey();
            for (Case testCase : entry.getValue()) {
                String where = transition + " из " + testCase.from().readiness() + "/"
                        + testCase.from().phase()
                        + (testCase.from().paused() ? " на паузе" : "");
                if (testCase.allowed()) {
                    assertThat(GameStateMachine.apply(testCase.from(), transition, "перерыв"))
                            .as(where)
                            .isNotNull();
                } else {
                    assertThatThrownBy(() -> GameStateMachine.apply(testCase.from(), transition, "перерыв"))
                            .as(where)
                            .isInstanceOf(ApiException.class)
                            .hasMessage(transition.reason());
                }
            }
        }
    }

    /** Переход без случая в таблице теста не проверен вовсе — это дыра, а не мелочь. */
    @Test
    void tableCoversEveryTransition() {
        assertThat(TABLE.keySet())
                .as("каждый переход обязан иметь случай в таблице")
                .containsExactlyInAnyOrder(GameTransition.values());
        TABLE.forEach((transition, cases) -> {
            assertThat(cases).as("у перехода %s есть разрешённый случай", transition)
                    .anyMatch(Case::allowed);
            assertThat(cases).as("у перехода %s есть запрещённый случай", transition)
                    .anyMatch(testCase -> !testCase.allowed());
        });
    }

    /** Отказ называет машинный код и причину: мастер должен понять, что именно менять. */
    @Test
    void refusalCarriesCodeAndReason() {
        assertThatThrownBy(() -> GameStateMachine.apply(finished(), GameTransition.START, null))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(GameTransition.START.code());
                    assertThat(exception.status().value()).isEqualTo(409);
                    assertThat(exception.getMessage()).isEqualTo(GameTransition.START.reason());
                });
    }

    /** `FINISHED` — конец: не проходит ни один переход, включая возврат к черновику (`D-094`). */
    @Test
    void finishedIsIrreversible() {
        for (GameTransition transition : GameTransition.values()) {
            assertThatThrownBy(() -> GameStateMachine.apply(finished(), transition, "причина"))
                    .as("после FINISHED переход %s запрещён", transition)
                    .isInstanceOf(ApiException.class);
        }
    }

    /** Пауза без причины выглядит для игрока как молчание мастера (`D-059`). */
    @Test
    void pauseRequiresAReason() {
        assertThatThrownBy(() -> GameStateMachine.apply(running(), GameTransition.PAUSE, null))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.code()).isEqualTo("pause_reason_required"));
        assertThatThrownBy(() -> GameStateMachine.apply(running(), GameTransition.PAUSE, "   "))
                .isInstanceOf(ApiException.class)
                .hasMessage("Укажите причину паузы");
    }

    @Test
    void pauseKeepsTheReasonAndResumeDropsIt() {
        GameState onPause = GameStateMachine.apply(running(), GameTransition.PAUSE, "  мастер ушёл  ");
        assertThat(onPause.paused()).isTrue();
        assertThat(onPause.pauseReason()).isEqualTo("мастер ушёл");

        GameState resumed = GameStateMachine.apply(onPause, GameTransition.RESUME, null);
        assertThat(resumed.paused()).isFalse();
        assertThat(resumed.pauseReason()).isNull();
    }

    /** Завершение снимает паузу: снять её после `FINISHED` уже нельзя, причина повисла бы навсегда. */
    @Test
    void finishClearsPause() {
        GameState result = GameStateMachine.apply(paused(), GameTransition.FINISH, null);

        assertThat(result.phase()).isEqualTo(Phase.FINISHED);
        assertThat(result.paused()).isFalse();
        assertThat(result.pauseReason()).isNull();
        assertThat(result.readOnly()).isTrue();
    }

    /** Оси независимы: старт не трогает ни готовность, ни видимость. */
    @Test
    void startKeepsReadinessAndVisibility() {
        GameState result = GameStateMachine.apply(ready(), GameTransition.START, null);

        assertThat(result.phase()).isEqualTo(Phase.RUNNING);
        assertThat(result.readiness()).isEqualTo(Readiness.READY);
        assertThat(result.visibility()).isEqualTo(Visibility.PRIVATE);
        assertThat(result.gameId()).isEqualTo(GAME);
    }

    /** Состояние — значение, а не курсор: применение перехода не мутирует вход. */
    @Test
    void applyReturnsANewStateAndLeavesTheInputAlone() {
        GameState before = draft();

        GameState after = GameStateMachine.apply(before, GameTransition.MARK_READY, null);

        assertThat(before.readiness()).isEqualTo(Readiness.DRAFT);
        assertThat(after.readiness()).isEqualTo(Readiness.READY);
        assertThat(after).isNotSameAs(before);
    }

    /** Откат до старта обратим: после него игру снова можно объявить готовой. */
    @Test
    void rollbackBeforeStartIsReversible() {
        GameState rollback = GameStateMachine.apply(ready(), GameTransition.RETURN_TO_DRAFT, null);
        assertThat(rollback.readiness()).isEqualTo(Readiness.DRAFT);
        assertThat(GameStateMachine.apply(rollback, GameTransition.MARK_READY, null).readiness())
                .isEqualTo(Readiness.READY);
    }

    /** Разрешённый переход из таблицы не бросает ничего, но причина паузы всё равно обязательна. */
    @Test
    void allowedPauseWithoutReasonIsStillRefused() {
        assertThat(GameTransition.PAUSE.allowedFrom(running()))
                .as("по осям пауза разрешена")
                .isTrue();
        assertThatThrownBy(() -> GameStateMachine.apply(running(), GameTransition.PAUSE, null))
                .as("но без причины не применяется")
                .isInstanceOf(ApiException.class);
    }
}
