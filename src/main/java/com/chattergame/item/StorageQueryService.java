package com.chattergame.item;

import com.chattergame.character.Character;
import com.chattergame.character.CharacterRepository;
import com.chattergame.character.Presence;
import com.chattergame.character.PresenceRepository;
import com.chattergame.game.Game;
import com.chattergame.game.GameRepository;
import com.chattergame.game.Membership;
import com.chattergame.game.MembershipRepository;
import com.chattergame.game.MembershipRole;
import com.chattergame.game.Readiness;
import com.chattergame.util.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Чтение содержимого хранилищ (`PLAN` E07, задачи 4–7). Правило видимости применяется здесь
 * и один раз, как и у персонажей: и локационное хранилище, и оба хранилища персонажа смотрят
 * через этот сервис, поэтому «скрытый не показывает ничего» и «содержимое сундука до
 * открытия не видно» не расходятся по запросам.
 *
 * <p>Видимость считается на чтении по прямому указанию `D-019` — в отличие от сообщений,
 * где `AGENTS §3` требует материализации в `inbox_entry`: там правило зависит от времени
 * отправки, здесь — только от текущего состояния мира.
 */
@Service
public class StorageQueryService {

    private final StorageRepository storages;
    private final ItemRepository items;
    private final CharacterRepository characters;
    private final PresenceRepository presence;
    private final GameRepository games;
    private final MembershipRepository memberships;

    public StorageQueryService(
            StorageRepository storages,
            ItemRepository items,
            CharacterRepository characters,
            PresenceRepository presence,
            GameRepository games,
            MembershipRepository memberships) {
        this.storages = storages;
        this.items = items;
        this.characters = characters;
        this.presence = presence;
        this.games = games;
        this.memberships = memberships;
    }

    /** Хранилища локационного чата глазами читающего: локация и сундуки. */
    @Transactional(readOnly = true)
    public List<VisibleStorage> inChat(UUID userId, UUID gameId, UUID chatId) {
        View view = view(userId, gameId);
        List<VisibleStorage> result = new ArrayList<>();
        for (Storage storage : storages.findByChat(chatId)) {
            VisibleStorage visible = present(storage, view);
            if (visible != null) {
                result.add(visible);
            }
        }
        return result;
    }

    /** Оба хранилища персонажа глазами читающего. */
    @Transactional(readOnly = true)
    public List<VisibleStorage> ofCharacter(UUID userId, UUID gameId, UUID characterId) {
        View view = view(userId, gameId);
        Character character = characters.findById(characterId)
                .orElseThrow(() -> ApiException.notFound("character_not_found", "Персонаж не найден"));
        if (!character.gameId().equals(gameId)) {
            throw ApiException.notFound("character_not_found", "Персонаж не найден");
        }
        List<VisibleStorage> result = new ArrayList<>();
        for (Storage storage : storages.findByCharacter(characterId)) {
            VisibleStorage visible = present(storage, view);
            if (visible != null) {
                result.add(visible);
            }
        }
        return result;
    }

    /**
     * Видимость одного хранилища. `null` — хранилище не показывается вовсе, а не приходит
     * помеченным: клиент не должен отличать «нет» от «есть, но скрыто».
     *
     * <p>Содержимое сундука до открытия не отдаётся ни строчкой: описание сундука видно,
     * а что внутри — нет (`REQUIREMENTS §10`).
     */
    private VisibleStorage present(Storage storage, View view) {
        if (view.master()) {
            return visible(storage, true);
        }
        return switch (storage.kind()) {
            case LOCATION -> {
                if (!presentIn(storage.chatId(), view)) {
                    yield null;
                }
                yield visible(storage, true);
            }
            case CHEST -> {
                if (!presentIn(storage.chatId(), view)) {
                    yield null;
                }
                yield visible(storage, storage.open());
            }
            case CHARACTER_HIDDEN -> {
                if (!owns(storage.characterId(), view)) {
                    yield null;
                }
                yield visible(storage, true);
            }
            // D-019: видимое хранилище персонажа видно ровно тем, кому виден сам владелец.
            // Скрытый владелец не показывает ничего, включая крупное оружие (D-017), и не
            // выдаёт даже самого хранилища: «невидимое не приходит помеченным» (D-030).
            case CHARACTER_VISIBLE -> {
                if (owns(storage.characterId(), view)) {
                    yield visible(storage, true);
                }
                if (!seesOwner(storage.characterId(), view)) {
                    yield null;
                }
                yield visible(storage, true);
            }
        };
    }

    /** Содержимое собирается по правилу видимости: у чужого крупного хранилища — только крупное. */
    private VisibleStorage visible(Storage storage, boolean contentsVisible) {
        List<VisibleItem> contents = new ArrayList<>();
        if (contentsVisible) {
            for (Item item : items.findByStorage(storage.id())) {
                if (visibleItem(storage, item)) {
                    contents.add(item(item));
                }
            }
        }
        return new VisibleStorage(
                storage.id(),
                storage.kind(),
                storage.chatId(),
                storage.characterId(),
                storage.title(),
                storage.description(),
                storage.imageAssetId(),
                storage.open(),
                contentsVisible,
                contents);
    }

    private boolean visibleItem(Storage storage, Item item) {
        // D-017: крупное оружие в видимом хранилище персонажа видно всем, кто видит владельца,
        // мелкое — нет. Мелкое туда попадает только рукой мастера, и это не повод показывать его.
        return storage.kind() != StorageKind.CHARACTER_VISIBLE || item.size() == ItemSize.LARGE;
    }

    /** Владелец хранилища — играющий этого персонажа (`REQUIREMENTS §9`). */
    private boolean owns(UUID characterId, View view) {
        if (characterId == null) {
            return false;
        }
        return characters.findById(characterId)
                .map(character -> character.occupantMembershipId() != null
                        && character.occupantMembershipId().equals(view.membershipId()))
                .orElse(false);
    }

    /** Виден ли владелец: скрытый и архивный не виден, свой персонаж виден всегда. */
    private boolean seesOwner(UUID characterId, View view) {
        Character character = characters.findById(characterId).orElse(null);
        if (character == null || character.archived()) {
            return false;
        }
        Presence placement = presence.findActive(characterId).orElse(null);
        return placement == null || !placement.hidden();
    }

    /** Хранилище локации видно тому, кто в ней присутствует; мастеру — везде (`REQUIREMENTS §7.1`). */
    private boolean presentIn(UUID chatId, View view) {
        if (chatId == null) {
            return false;
        }
        for (UUID characterId : viewerCharacters(view)) {
            Presence placement = presence.findActive(characterId).orElse(null);
            if (placement != null && chatId.equals(placement.chatId())) {
                return true;
            }
        }
        return false;
    }

    /** Персонажи игрока — те, что он занимает. Занятость появляется на `E13` (`D-109`). */
    private List<UUID> viewerCharacters(View view) {
        return characters.findByOccupant(view.membershipId()).stream().map(Character::id).toList();
    }

    private View view(UUID userId, UUID gameId) {
        Game game = games.findById(gameId)
                .orElseThrow(() -> ApiException.notFound("game_not_found", "Игра не найдена"));
        Membership membership = memberships.find(gameId, userId)
                .orElseThrow(() -> ApiException.forbidden("not_a_member", "Нет доступа к этой игре"));
        if (membership.banned()) {
            throw ApiException.forbidden("membership_banned", "Участие в этой игре запрещено мастером");
        }
        return new View(
                membership.role() == MembershipRole.MASTER,
                membership.id(),
                game.state().readiness() == Readiness.READY);
    }

    private static VisibleItem item(Item item) {
        return new VisibleItem(
                item.id(),
                item.type(),
                item.name(),
                item.description(),
                item.imageAssetId(),
                item.size(),
                item.quantity(),
                item.properties());
    }

    private record View(boolean master, UUID membershipId, boolean ready) {
    }

    /** Хранилище в ответе API вместе с той частью содержимого, которую читающий вправе видеть. */
    public record VisibleStorage(
            UUID id,
            StorageKind kind,
            UUID chatId,
            UUID characterId,
            String title,
            String description,
            UUID imageAssetId,
            boolean open,
            boolean contentsVisible,
            List<VisibleItem> items) {
    }

    public record VisibleItem(
            UUID id,
            ItemType type,
            String name,
            String description,
            UUID imageAssetId,
            ItemSize size,
            int quantity,
            String properties) {
    }
}
