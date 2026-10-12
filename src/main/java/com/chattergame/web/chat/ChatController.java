package com.chattergame.web.chat;

import com.chattergame.chat.Chat;
import com.chattergame.chat.ChatGraphService;
import com.chattergame.chat.EdgeState;
import com.chattergame.chat.GameGraph;
import com.chattergame.chat.Transition;
import com.chattergame.chat.TransitionState;
import com.chattergame.game.Game;
import com.chattergame.game.GameMutationService;
import com.chattergame.game.GameQueryService;
import com.chattergame.util.ApiException;
import com.chattergame.web.AuthContext;
import com.chattergame.web.AuthContextResolver;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Локации, переходы и граф (`PLAN` E04). Контроллер не пишет в доменные таблицы: любая
 * мутация идёт через {@link GameMutationService} (`D-034`), чтение — через
 * {@link ChatGraphService}, чтобы правило видимости применялось в одном месте (`D-030`).
 *
 * <p>Пути переходов лежат под `/graph/transitions`, а не под `/transitions`: последний уже
 * занят переходами состояния игры (`E03`), и два разных словаря на одном пути разошлись бы
 * при первом же чтении вслух.
 *
 * <p>`DELETE` присутствует, но всегда отказывает: физического удаления нет ни до, ни после
 * `READY` (`D-010`, `D-103`). Отдельный метод нужен, чтобы клиент получил внятный код и
 * причину, а не `404` на несуществующий маршрут.
 */
@RestController
@RequestMapping("/games/{gameId}")
public class ChatController {

    private final GameMutationService mutations;
    private final GameQueryService queries;
    private final ChatGraphService graph;

    public ChatController(
            GameMutationService mutations, GameQueryService queries, ChatGraphService graph) {
        this.mutations = mutations;
        this.queries = queries;
        this.graph = graph;
    }

    // --- Локации -----------------------------------------------------------------------

    @PostMapping("/locations")
    public ResponseEntity<ChatView> createLocation(
            @PathVariable UUID gameId, @Valid @RequestBody LocationRequest request, AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        Chat chat = mutations.createLocation(
                auth.user().id(), gameId, request.title(), request.description());
        return ResponseEntity.status(HttpStatus.CREATED).body(ChatView.of(chat));
    }

    /** Локации игры глазами запросившего; архивация до `READY` игроку не показывается (`D-009`). */
    @GetMapping("/locations")
    public List<GameGraph.Node> locations(
            @PathVariable UUID gameId,
            @RequestParam(required = false) UUID asCharacter,
            AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        return graph(graphIdReadable(auth, gameId), auth.user().id(), asCharacter).nodes();
    }

    @PatchMapping("/locations/{chatId}")
    public ChatView updateLocation(
            @PathVariable UUID gameId,
            @PathVariable UUID chatId,
            @Valid @RequestBody LocationRequest request,
            AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        return ChatView.of(mutations.updateLocation(
                auth.user().id(), gameId, chatId, request.title(), request.description()));
    }

    @PostMapping("/locations/{chatId}/archive")
    public ChatView archiveLocation(
            @PathVariable UUID gameId,
            @PathVariable UUID chatId,
            @RequestBody(required = false) ArchiveRequest request,
            AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        String reason = request == null ? null : request.reason();
        return ChatView.of(mutations.archiveLocation(auth.user().id(), gameId, chatId, reason));
    }

    /**
     * Картинка локации (`E05`, `D-106`). `PUT`, а не `POST`: вызов задаёт итоговое состояние
     * ссылки, и повтор с тем же телом ничего не меняет. Пустое `assetId` снимает картинку.
     */
    @PutMapping("/locations/{chatId}/image")
    public ChatView setLocationImage(
            @PathVariable UUID gameId,
            @PathVariable UUID chatId,
            @RequestBody LocationImageRequest request,
            AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        return ChatView.of(mutations.setLocationImage(
                auth.user().id(), gameId, chatId, request == null ? null : request.assetId()));
    }

    @DeleteMapping("/locations/{chatId}")
    public ResponseEntity<Void> deleteLocation(
            @PathVariable UUID gameId, @PathVariable UUID chatId, AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        mutations.rejectLocationDeletion(auth.user().id(), gameId, chatId);
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    // --- Переходы ----------------------------------------------------------------------

    @PostMapping("/graph/transitions")
    public ResponseEntity<TransitionView> createTransition(
            @PathVariable UUID gameId, @Valid @RequestBody TransitionRequest request, AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        Transition transition = mutations.createTransition(
                auth.user().id(), gameId, request.fromChatId(), request.toChatId(), request.title());
        return ResponseEntity.status(HttpStatus.CREATED).body(TransitionView.of(transition));
    }

    @GetMapping("/graph/transitions")
    public List<GameGraph.Edge> transitions(
            @PathVariable UUID gameId,
            @RequestParam(required = false) UUID asCharacter,
            AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        return graph(graphIdReadable(auth, gameId), auth.user().id(), asCharacter).edges();
    }

    @PatchMapping("/graph/transitions/{transitionId}")
    public TransitionView renameTransition(
            @PathVariable UUID gameId,
            @PathVariable UUID transitionId,
            @RequestBody TransitionRequest request,
            AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        return TransitionView.of(
                mutations.renameTransition(auth.user().id(), gameId, transitionId, request.title()));
    }

    @PostMapping("/graph/transitions/{transitionId}/archive")
    public TransitionView archiveTransition(
            @PathVariable UUID gameId,
            @PathVariable UUID transitionId,
            @RequestBody(required = false) ArchiveRequest request,
            AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        String reason = request == null ? null : request.reason();
        return TransitionView.of(
                mutations.archiveTransition(auth.user().id(), gameId, transitionId, reason));
    }

    @DeleteMapping("/graph/transitions/{transitionId}")
    public ResponseEntity<Void> deleteTransition(
            @PathVariable UUID gameId, @PathVariable UUID transitionId, AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        mutations.rejectTransitionDeletion(auth.user().id(), gameId, transitionId);
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    /**
     * Знание ребра персонажем. Идемпотентно: повторная выдача не падает, а перезаписывает
     * состояние — мастер может нажать кнопку дважды, и мир от этого не разъедется.
     */
    @PutMapping("/graph/transitions/{transitionId}/state")
    public Map<String, Object> setEdgeState(
            @PathVariable UUID gameId,
            @PathVariable UUID transitionId,
            @Valid @RequestBody EdgeStateRequest request,
            AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        TransitionState stored = mutations.setEdgeState(
                auth.user().id(),
                gameId,
                transitionId,
                request.characterId(),
                parseEdgeState(request.state()),
                request.reason());
        return Map.of(
                "transitionId", stored.transitionId().toString(),
                "characterId", stored.characterId().toString(),
                "state", stored.state().name(),
                "reason", stored.reason() == null ? "" : stored.reason());
    }

    // --- Граф --------------------------------------------------------------

    /** Граф целиком: узлы и рёбра глазами запросившего (`PLAN` E04, задача 6). */
    @GetMapping("/graph")
    public GameGraph readGraph(
            @PathVariable UUID gameId,
            @RequestParam(required = false) UUID asCharacter,
            AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        return graph(graphIdReadable(auth, gameId), auth.user().id(), asCharacter);
    }

    private GameGraph graph(Game game, UUID userId, UUID asCharacter) {
        return graph.graphFor(game, userId, asCharacter);
    }

    /** Право чтения игры проверяется до обращения к графу: иначе отказ выдал бы её существование. */
    private Game graphIdReadable(AuthContext auth, UUID gameId) {
        return queries.requireReadable(auth.user().id(), gameId);
    }

    private static EdgeState parseEdgeState(String value) {
        try {
            return EdgeState.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("unknown_edge_state", "Такого состояния перехода нет: " + value);
        }
    }
}
