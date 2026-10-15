package com.chattergame.web.chat;

import com.chattergame.character.Character;
import com.chattergame.character.CharacterQueryService;
import com.chattergame.character.Presence;
import com.chattergame.game.GameMutationService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Персонажи, присутствие и скрытность (`PLAN` E06). Контроллер не пишет в доменные таблицы:
 * любая мутация идёт через {@link GameMutationService} (`D-034`), чтение — через
 * {@link CharacterQueryService}, чтобы правило видимости применялось в одном месте,
 * как и у графа (`D-030`).
 *
 * <p>`DELETE` присутствует и всегда отказывает: физического удаления персонажа нет ни до,
 * ни после `READY` (`D-009`, `D-010`). Отдельный метод нужен, чтобы клиент получил внятный
 * код и причину, а не `404` на несуществующий маршрут.
 */
@RestController
@RequestMapping("/games/{gameId}/characters")
public class CharacterController {

    private final GameMutationService mutations;
    private final CharacterQueryService characters;

    public CharacterController(GameMutationService mutations, CharacterQueryService characters) {
        this.mutations = mutations;
        this.characters = characters;
    }

    @PostMapping
    public ResponseEntity<CharacterView> create(
            @PathVariable UUID gameId, @Valid @RequestBody CharacterRequest request, AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        Character character = mutations.createCharacter(
                auth.user().id(),
                gameId,
                request.name(),
                request.portraitAssetId(),
                request.appearance(),
                request.publicHistory(),
                request.privateHistory(),
                request.playable(),
                request.npc());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(CharacterView.of(characters.card(auth.user().id(), gameId, character.id())));
    }

    /** Персонажи игры глазами запросившего: скрытые и закрытые истории отсеивает сервис чтения. */
    @GetMapping
    public List<CharacterView> list(@PathVariable UUID gameId, AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        return characters.list(auth.user().id(), gameId).stream().map(CharacterView::of).toList();
    }

    @GetMapping("/{characterId}")
    public CharacterView card(
            @PathVariable UUID gameId, @PathVariable UUID characterId, AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        return CharacterView.of(characters.card(auth.user().id(), gameId, characterId));
    }

    @PatchMapping("/{characterId}")
    public CharacterView update(
            @PathVariable UUID gameId,
            @PathVariable UUID characterId,
            @Valid @RequestBody CharacterRequest request,
            AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        mutations.updateCharacterCard(
                auth.user().id(),
                gameId,
                characterId,
                request.name(),
                request.portraitAssetId(),
                request.appearance(),
                request.publicHistory(),
                request.privateHistory(),
                request.playable(),
                request.npc());
        return CharacterView.of(characters.card(auth.user().id(), gameId, characterId));
    }

    @PostMapping("/{characterId}/archive")
    public CharacterView archive(
            @PathVariable UUID gameId,
            @PathVariable UUID characterId,
            @RequestBody(required = false) ArchiveRequest request,
            AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        String reason = request == null ? null : request.reason();
        mutations.archiveCharacter(auth.user().id(), gameId, characterId, reason);
        return CharacterView.of(characters.card(auth.user().id(), gameId, characterId));
    }

    /**
     * Размещение и перемещение. `PUT`, а не `POST`: вызов задаёт итоговое положение персонажа,
     * и повтор с тем же телом ничего не меняет.
     */
    @PutMapping("/{characterId}/location")
    public Map<String, Object> place(
            @PathVariable UUID gameId,
            @PathVariable UUID characterId,
            @Valid @RequestBody PlacementRequest request,
            AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        Presence placed = mutations.placeCharacter(auth.user().id(), gameId, characterId, request.chatId());
        return Map.of(
                "characterId", placed.characterId().toString(),
                "chatId", placed.chatId().toString(),
                "hidden", placed.hidden());
    }

    @PutMapping("/{characterId}/stealth")
    public Map<String, Object> stealth(
            @PathVariable UUID gameId,
            @PathVariable UUID characterId,
            @Valid @RequestBody StealthRequest request,
            AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        Presence stored = mutations.setCharacterStealth(
                auth.user().id(), gameId, characterId, request.hidden(), request.reason());
        return Map.of(
                "characterId", stored.characterId().toString(),
                "chatId", stored.chatId().toString(),
                "hidden", stored.hidden());
    }

    /**
     * Занятие персонажа игроком (`PLAN` E13, задача 2). `PUT`, а не `POST`: вызов задаёт
     * итоговое состояние слота, и повтор ничего не меняет. Захват идёт под блокировкой
     * строки персонажа (`D-100`), поэтому двух победителей в гонке не бывает.
     */
    @PutMapping("/{characterId}/occupation")
    public CharacterView occupy(
            @PathVariable UUID gameId, @PathVariable UUID characterId, AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        mutations.claimCharacter(auth.user().id(), gameId, characterId);
        return CharacterView.of(characters.card(auth.user().id(), gameId, characterId));
    }

    /** Освобождение своего персонажа: слот становится свободным и может достаться другому. */
    @DeleteMapping("/{characterId}/occupation")
    public CharacterView release(
            @PathVariable UUID gameId, @PathVariable UUID characterId, AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        mutations.releaseCharacter(auth.user().id(), gameId, characterId);
        return CharacterView.of(characters.card(auth.user().id(), gameId, characterId));
    }

    /**
     * Перемещение своим персонажем по открытому переходу (`REQUIREMENTS §8`). Мастер двигает
     * кого угодно куда угодно (`PUT /location`), игрок идёт только по ребру, которое для
     * этого персонажа `AVAILABLE`.
     */
    @PostMapping("/{characterId}/move")
    public Map<String, Object> move(
            @PathVariable UUID gameId,
            @PathVariable UUID characterId,
            @Valid @RequestBody CharacterMoveRequest request,
            AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        Presence placed = mutations.moveCharacter(
                auth.user().id(), gameId, characterId, request.transitionId());
        return Map.of(
                "characterId", placed.characterId().toString(),
                "chatId", placed.chatId().toString(),
                "hidden", placed.hidden());
    }

    @DeleteMapping("/{characterId}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID gameId, @PathVariable UUID characterId, AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        mutations.rejectCharacterDeletion(auth.user().id(), gameId, characterId);
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }
}
