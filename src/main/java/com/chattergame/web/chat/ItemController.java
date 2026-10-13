package com.chattergame.web.chat;

import com.chattergame.game.GameMutationService;
import com.chattergame.item.Item;
import com.chattergame.item.StorageQueryService;
import com.chattergame.web.AuthContext;
import com.chattergame.web.AuthContextResolver;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Предметы (`PLAN` E07, задача 2). Любая запись идёт через {@link GameMutationService}
 * (`D-034`): контроллер не касается доменных таблиц, а `ITEM_MOVED` пишется сервисом
 * вместе с эффектом, иначе журнал перестанет воспроизводить размещение.
 *
 * <p>Чтения отдельным маршрутом нет намеренно: предмет не живёт сам по себе, и его карточка
 * приходит вместе с хранилищем, где правило видимости уже применено (`D-019`).
 */
@RestController
@RequestMapping("/games/{gameId}/items")
public class ItemController {

    private final GameMutationService mutations;

    public ItemController(GameMutationService mutations) {
        this.mutations = mutations;
    }

    @PostMapping
    public ResponseEntity<ItemView> create(
            @PathVariable UUID gameId, @Valid @RequestBody ItemRequest request, AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        Item item = mutations.createItem(
                auth.user().id(),
                gameId,
                request.type(),
                request.name(),
                request.description(),
                request.imageAssetId(),
                request.size(),
                request.quantity(),
                request.properties(),
                request.storageId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ItemView.of(item));
    }

    @PatchMapping("/{itemId}")
    public ItemView update(
            @PathVariable UUID gameId,
            @PathVariable UUID itemId,
            @Valid @RequestBody ItemRequest request,
            AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        Item item = mutations.updateItem(
                auth.user().id(),
                gameId,
                itemId,
                request.type(),
                request.name(),
                request.description(),
                request.size(),
                request.quantity(),
                request.properties());
        return ItemView.of(item);
    }

    @PostMapping("/{itemId}/image")
    public ItemView image(
            @PathVariable UUID gameId,
            @PathVariable UUID itemId,
            @RequestBody ImageRequest request,
            AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        Item item = mutations.setItemImage(
                auth.user().id(), gameId, itemId, request == null ? null : request.assetId());
        return ItemView.of(item);
    }

    @PostMapping("/{itemId}/move")
    public ItemView move(
            @PathVariable UUID gameId,
            @PathVariable UUID itemId,
            @Valid @RequestBody ItemMoveRequest request,
            AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        return ItemView.of(mutations.moveItem(
                        auth.user().id(),
                        gameId,
                        itemId,
                        request.fromStorageId(),
                        request.storageId(),
                        request.quantity())
                .item());
    }
}
