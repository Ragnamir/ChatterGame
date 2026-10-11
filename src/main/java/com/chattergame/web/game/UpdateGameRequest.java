package com.chattergame.web.game;

import jakarta.validation.constraints.Size;

/**
 * Правка игры. Поля необязательны: `PATCH` меняет только переданное, а `null` в названии
 * или видимости означает «не трогать», а не «очистить». Отдельного «очистить описание»
 * нет намеренно — пустая строка и отсутствие поля различимы и так.
 */
public record UpdateGameRequest(
        @Size(max = 200) String title,
        @Size(max = 20000) String publicDescription,
        @Size(max = 20000) String privateDescription,
        String visibility) {
}
