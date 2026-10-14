package com.chattergame.turn;

import java.time.Instant;
import java.util.UUID;

/**
 * Ход игры (`REQUIREMENTS §14`) — единственная механика времени в проекте (`D-011`).
 *
 * <p>`n` растёт монотонно в пределах игры и никогда не переиспользуется: строка хода
 * не удаляется и не переписывается, закрытие лишь проставляет статус и момент (`D-010`).
 * Номера нет в `game_event` как отдельного поля — он в `payload` события `TURN_OPENED`,
 * потому что журнал и таблица обязаны читаться вместе, а не расходиться.
 *
 * <p>`closedBy` и `closedAt` пусты у открытого хода. Ни одно из времён не участвует
 * в решениях сервиса: они для аудита и отображения, а не для вычислений (`D-060`).
 */
public record Turn(
        UUID id,
        UUID gameId,
        long n,
        TurnStatus status,
        Instant openedAt,
        UUID closedBy,
        Instant closedAt) {

    public boolean open() {
        return status == TurnStatus.OPEN;
    }
}
