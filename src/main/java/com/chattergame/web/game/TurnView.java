package com.chattergame.web.game;

import com.chattergame.turn.TurnBudget;
import com.chattergame.turn.TurnState;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Счётчик хода в ответе API (`PLAN` E10, `F03`).
 *
 * <p>Отдельного поля «ожидание мастера» нет намеренно: клиент читает `open == false`.
 * Появление такого поля подтолкнуло бы считать его по времени, а таймаута в проекте
 * нет (`D-060`), и «ожидание» — это отображение факта, а не вычисление.
 */
public record TurnView(
        boolean turnBased,
        boolean open,
        long n,
        Instant openedAt,
        Instant closedAt,
        List<Budget> budgets) {

    public record Budget(UUID characterId, boolean actionUsed, boolean speechUsed) {

        static Budget of(TurnBudget budget) {
            return new Budget(budget.characterId(), budget.actionUsed(), budget.speechUsed());
        }
    }

    public static TurnView of(TurnState state) {
        return new TurnView(
                state.turnBased(),
                state.open(),
                state.n(),
                state.openedAt(),
                state.closedAt(),
                state.budgets().stream().map(Budget::of).toList());
    }
}
