package com.chattergame.web.game;

import com.chattergame.game.Game;
import com.chattergame.game.GameState;

import java.time.Instant;
import java.util.UUID;

/**
 * Игра в ответе API. Состояние отдаётся тремя отдельными полями, а не одной строкой:
 * оси независимы (`D-003`), и клиент не должен разбирать составное значение, чтобы понять,
 * что именно изменилось.
 *
 * <p>Закрытое описание присутствует в схеме намеренно: его видит мастер, а право на чтение
 * проверяется в контроллере, а не вырезанием поля из общего DTO.
 */
public record GameView(
        UUID id,
        UUID masterUserId,
        String title,
        String publicDescription,
        String privateDescription,
        String readiness,
        String visibility,
        String phase,
        boolean paused,
        String pauseReason,
        boolean turnBased,
        boolean archived,
        String archivedReason,
        boolean readOnly,
        Instant createdAt,
        Instant updatedAt) {

    public static GameView of(Game game) {
        GameState state = game.state();
        return new GameView(
                game.id(),
                game.masterUserId(),
                game.title(),
                game.publicDescription(),
                game.privateDescription(),
                state.readiness().name(),
                state.visibility().name(),
                state.phase().name(),
                state.paused(),
                state.pauseReason(),
                game.turnBased(),
                game.archived(),
                game.archivedReason(),
                game.readOnly(),
                game.createdAt(),
                game.updatedAt());
    }
}
