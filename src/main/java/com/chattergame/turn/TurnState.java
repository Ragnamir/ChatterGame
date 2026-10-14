package com.chattergame.turn;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Счётчик хода как его видит клиент (`PLAN` E10, `F03`).
 *
 * <p>`n` — номер последнего хода, `0` если ход не открывали ни разу; `open` — открыт ли он
 * прямо сейчас. Отдельного поля «ожидание мастера» нет: `open = false` и есть это состояние,
 * а не вычисление по времени (`D-060`) — время в записи присутствует только для аудита и
 * отображения момента, но ни одно решение по нему не принимается.
 *
 * <p>`budgets` — расход по персонажам. Мастер видит все, игрок — только своих, поэтому
 * список собирается под смотрящего, а не отдаётся целиком с фильтрацией на клиенте.
 */
public record TurnState(
        boolean turnBased,
        boolean open,
        long n,
        Instant openedAt,
        Instant closedAt,
        List<TurnBudget> budgets) {

    public static TurnState of(boolean turnBased, Turn turn, List<TurnBudget> budgets) {
        if (turn == null) {
            return new TurnState(turnBased, false, 0L, null, null, List.of());
        }
        return new TurnState(
                turnBased, turn.open(), turn.n(), turn.openedAt(), turn.closedAt(), budgets);
    }
}
