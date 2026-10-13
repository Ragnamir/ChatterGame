package com.chattergame.realtime;

import java.util.UUID;

/**
 * Ссылка на событие журнала — всё, что несёт фан-аут (`D-084`). Тело события не передаётся:
 * `NOTIFY` ограничен 8 KB и не даёт гарантий доставки, поэтому уведомление служит только
 * триггером, а содержимое читается из таблицы по номеру (`D-033`).
 */
public record GameEventRef(UUID gameId, long seq) {

    @Override
    public String toString() {
        return gameId + ":" + seq;
    }

    /** Разбор полезной нагрузки уведомления. Формат один на обе реализации. */
    public static GameEventRef parse(String payload) {
        int separator = payload.indexOf(':');
        if (separator <= 0) {
            throw new IllegalArgumentException("Ссылка события без игры и номера: " + payload);
        }
        return new GameEventRef(
                UUID.fromString(payload.substring(0, separator)),
                Long.parseLong(payload.substring(separator + 1)));
    }
}
