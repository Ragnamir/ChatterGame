package com.chattergame.web.game;

import com.chattergame.game.GameMutationService;
import com.chattergame.turn.TurnService;
import com.chattergame.web.AuthContext;
import com.chattergame.web.AuthContextResolver;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Счётчик хода (`PLAN` E10): чтение состояния, открытие и закрытие хода.
 *
 * <p>Открытие и закрытие живут маршрутами, а не полем правки игры: это события
 * `TURN_OPENED` и `TURN_CLOSED`, и каждое обязано быть видно в журнале отдельной записью
 * (`D-033`). Флаг режима правится общим `PATCH /games/{id}` вместе с прочими полями игры.
 *
 * <p>Право мастера проверяет {@link GameMutationService}, а не контроллер: смена хода
 * придёт и из других мест, и условие обязано быть одно.
 */
@RestController
@RequestMapping("/games/{gameId}/turn")
public class TurnController {

    private final TurnService turns;
    private final GameMutationService mutations;

    public TurnController(TurnService turns, GameMutationService mutations) {
        this.turns = turns;
        this.mutations = mutations;
    }

    @GetMapping
    public TurnView state(@PathVariable UUID gameId, AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        return TurnView.of(turns.state(auth.user().id(), gameId));
    }

    @PostMapping("/open")
    public TurnView open(@PathVariable UUID gameId, AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        mutations.openTurn(auth.user().id(), gameId);
        return TurnView.of(turns.state(auth.user().id(), gameId));
    }

    @PostMapping("/close")
    public TurnView close(@PathVariable UUID gameId, AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        mutations.closeTurn(auth.user().id(), gameId);
        return TurnView.of(turns.state(auth.user().id(), gameId));
    }
}
