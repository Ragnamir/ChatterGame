package com.chattergame.web.chat;

import com.chattergame.game.GameMutationService;
import com.chattergame.item.Storage;
import com.chattergame.item.StorageQueryService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Хранилища (`PLAN` E07, задачи 2 и 4). Запись — только через {@link GameMutationService}
 * (`D-034`), чтение — через {@link StorageQueryService}, чтобы правило видимости
 * применялось в одном месте, как у персонажей и графа (`D-030`).
 *
 * <p>Открытие хранилища — отдельный маршрут, а не поле в правке карточки: доступ меняет
 * то, что видят другие, и в журнале это событие `STORAGE_ACCESS_CHANGED`, а не `CONTENT`.
 */
@RestController
@RequestMapping("/games/{gameId}")
public class StorageController {

    private final GameMutationService mutations;
    private final StorageQueryService storages;

    public StorageController(GameMutationService mutations, StorageQueryService storages) {
        this.mutations = mutations;
        this.storages = storages;
    }

    @PostMapping("/storages")
    public ResponseEntity<StorageView> create(
            @PathVariable UUID gameId, @Valid @RequestBody StorageRequest request, AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        Storage storage = mutations.createStorage(
                auth.user().id(),
                gameId,
                request.kind(),
                request.chatId(),
                request.characterId(),
                request.title(),
                request.description());
        return ResponseEntity.status(HttpStatus.CREATED).body(StorageView.of(storage));
    }

    @PatchMapping("/storages/{storageId}")
    public StorageView update(
            @PathVariable UUID gameId,
            @PathVariable UUID storageId,
            @RequestBody StorageRequest request,
            AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        return StorageView.of(mutations.updateStorage(
                auth.user().id(), gameId, storageId, request.title(), request.description()));
    }

    @PostMapping("/storages/{storageId}/image")
    public StorageView image(
            @PathVariable UUID gameId,
            @PathVariable UUID storageId,
            @RequestBody ImageRequest request,
            AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        return StorageView.of(mutations.setStorageImage(
                auth.user().id(), gameId, storageId, request == null ? null : request.assetId()));
    }

    @PostMapping("/storages/{storageId}/access")
    public StorageView access(
            @PathVariable UUID gameId,
            @PathVariable UUID storageId,
            @Valid @RequestBody StorageOpenRequest request,
            AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        return StorageView.of(mutations.setStorageOpen(
                auth.user().id(), gameId, storageId, Boolean.TRUE.equals(request.open())));
    }

    /** Хранилища локации: локационное и сундуки, глазами запросившего (`REQUIREMENTS §7.1`). */
    @GetMapping("/locations/{chatId}/storages")
    public List<StorageView> inLocation(
            @PathVariable UUID gameId, @PathVariable UUID chatId, AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        return StorageView.of(storages.inChat(auth.user().id(), gameId, chatId));
    }

    /** Оба хранилища персонажа: своему владельцу и мастеру (`D-019`). */
    @GetMapping("/characters/{characterId}/storages")
    public List<StorageView> ofCharacter(
            @PathVariable UUID gameId, @PathVariable UUID characterId, AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        return StorageView.of(storages.ofCharacter(auth.user().id(), gameId, characterId));
    }
}
