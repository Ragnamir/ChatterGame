package com.chattergame.web.game;

import com.chattergame.game.Game;
import com.chattergame.game.GameMutationService;
import com.chattergame.game.GameQueryService;
import com.chattergame.game.GameTransition;
import com.chattergame.game.Membership;
import com.chattergame.game.MembershipRepository;
import com.chattergame.game.MembershipRole;
import com.chattergame.game.ReadinessReport;
import com.chattergame.game.ReadinessValidation;
import com.chattergame.game.Visibility;
import com.chattergame.journal.GameEventType;
import com.chattergame.util.ApiException;
import com.chattergame.web.AuthContext;
import com.chattergame.web.AuthContextResolver;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Игры: создание, чтение, переходы состояния и аудит-чтение журнала (`PLAN` E03).
 *
 * <p>Контроллер не пишет в доменные таблицы сам: любая мутация идёт через
 * {@link GameMutationService} (`AGENTS §2`, `D-034`). Здесь остаются только разбор запроса,
 * проверка «пришёл ли живой пользователь» и выбор формы ответа.
 *
 * <p>Право мастера проверяет сервис, а не контроллер: тот же переход придёт из заявки
 * игрока в `E14`, и условие обязано быть одно.
 */
@RestController
@RequestMapping("/games")
public class GameController {

    private final GameMutationService mutations;
    private final GameQueryService queries;
    private final MembershipRepository memberships;
    private final ReadinessValidation readiness;

    public GameController(
            GameMutationService mutations,
            GameQueryService queries,
            MembershipRepository memberships,
            ReadinessValidation readiness) {
        this.mutations = mutations;
        this.queries = queries;
        this.memberships = memberships;
        this.readiness = readiness;
    }

    @PostMapping
    public ResponseEntity<GameView> create(
            @Valid @RequestBody CreateGameRequest request, AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        Game game = mutations.createGame(
                auth.user().id(), request.title(), request.publicDescription(), request.privateDescription());
        return ResponseEntity.status(HttpStatus.CREATED).body(GameView.of(game));
    }

    @GetMapping
    public List<GameView> mine(AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        return queries.myGames(auth.user().id()).stream().map(GameView::of).toList();
    }

    @GetMapping("/{id}")
    public GameView read(@PathVariable UUID id, AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        return GameView.of(queries.requireReadable(auth.user().id(), id));
    }

    @PatchMapping("/{id}")
    public GameView update(
            @PathVariable UUID id, @Valid @RequestBody UpdateGameRequest request, AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        Game game = queries.requireReadable(auth.user().id(), id);

        if (request.title() != null || request.publicDescription() != null
                || request.privateDescription() != null) {
            game = mutations.updateContent(
                    auth.user().id(),
                    id,
                    request.title() == null ? game.title() : request.title(),
                    request.publicDescription() == null ? game.publicDescription() : request.publicDescription(),
                    request.privateDescription() == null ? game.privateDescription() : request.privateDescription());
        }
        if (request.visibility() != null) {
            game = mutations.changeVisibility(auth.user().id(), id, parseVisibility(request.visibility()));
        }
        if (request.turnBased() != null) {
            game = mutations.setTurnBased(auth.user().id(), id, request.turnBased());
        }
        return GameView.of(game);
    }

    /** Переход состояния. Отказ несёт код и текст причины (`REQUIREMENTS §4`). */
    @PostMapping("/{id}/transitions")
    public GameView transition(
            @PathVariable UUID id, @Valid @RequestBody TransitionRequest request, AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        GameTransition transition = parseTransition(request.transition());
        Game game = mutations.applyTransition(auth.user().id(), id, transition, request.pauseReason());
        return GameView.of(game);
    }

    /**
     * Доступные мастеру переходы из текущего состояния. Клиент показывает кнопки по этому
     * ответу, а не по своей копии таблицы переходов: иначе правило живёт в двух местах и
     * расходится при первом изменении (`D-003`).
     */
    @GetMapping("/{id}/transitions")
    public Map<String, Object> availableTransitions(@PathVariable UUID id, AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        Game game = queries.requireReadable(auth.user().id(), id);
        Membership membership = memberships.find(id, auth.user().id()).orElseThrow();
        boolean master = membership.role() == MembershipRole.MASTER;

        List<Map<String, String>> available = master
                ? java.util.Arrays.stream(GameTransition.values())
                        .filter(transition -> transition.allowedFrom(game.state()))
                        .map(transition -> Map.of("transition", transition.name(), "reason", transition.reason()))
                        .toList()
                : List.of();
        return Map.of("transitions", available);
    }

    /**
     * Архивирование игры (`D-010`). Единственный путь «снять игру с глаз»: удаления нет,
     * а участники продолжают читать её в том состоянии, в котором она остановилась.
     * Повторный вызов идемпотентен и не оставляет второго события.
     */
    @PostMapping("/{id}/archive")
    public GameView archive(
            @PathVariable UUID id, @Valid @RequestBody ArchiveGameRequest request, AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        mutations.archiveGame(auth.user().id(), id, request.reason());
        return GameView.of(queries.requireReadable(auth.user().id(), id));
    }

    /** Валидация готовности (`REQUIREMENTS §20`): свод проверок включён целиком на `E07` (`D-112`). */
    @GetMapping("/{id}/readiness")
    public ReadinessReport readiness(@PathVariable UUID id, AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        queries.requireReadable(auth.user().id(), id);
        return readiness.validate(id);
    }

    @GetMapping("/{id}/events")
    public List<GameEventView> events(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") long after,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "100") int limit,
            AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        GameEventType eventType = type == null || type.isBlank() ? null : parseType(type);
        return queries.events(auth.user().id(), id, after, eventType, limit).stream()
                .map(GameEventView::of)
                .toList();
    }

    private static GameTransition parseTransition(String value) {
        try {
            return GameTransition.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("unknown_transition", "Такого перехода нет: " + value);
        }
    }

    private static GameEventType parseType(String value) {
        try {
            return GameEventType.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("unknown_event_type", "Такого типа события нет: " + value);
        }
    }

    private static Visibility parseVisibility(String value) {
        try {
            return Visibility.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("unknown_visibility", "Такой видимости нет: " + value);
        }
    }
}
