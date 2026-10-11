package com.chattergame.game;

import java.time.Instant;
import java.util.UUID;

/**
 * Игра: контейнер мира (`REQUIREMENTS §3`). Состояние живёт отдельным значением
 * {@link GameState}, потому что переходы трогают только его, а содержимое — только своё.
 *
 * <p>`masterUserId` неизменяем: мастерство не передаётся (`D-021`), поэтому смена мастера
 * невозможна как операция, а не как запрет в коде.
 */
public record Game(
        UUID id,
        UUID masterUserId,
        String title,
        String publicDescription,
        String privateDescription,
        GameState state,
        boolean turnBased,
        boolean archived,
        String archivedReason,
        UUID finalChatId,
        Instant createdAt,
        Instant updatedAt) {

    /** Максимум колонки `game.title`; проверяется на сервисе ради текста ошибки, не только БД. */
    public static final int TITLE_MAX = 200;

    public boolean readOnly() {
        return state.readOnly();
    }
}
