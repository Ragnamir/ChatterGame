package com.chattergame.game;

import com.chattergame.character.Character;
import com.chattergame.character.CharacterRepository;
import com.chattergame.character.Presence;
import com.chattergame.character.PresenceRepository;
import com.chattergame.chat.Chat;
import com.chattergame.chat.ChatKind;
import com.chattergame.chat.ChatRepository;
import com.chattergame.chat.Transition;
import com.chattergame.chat.TransitionRepository;
import com.chattergame.item.ItemRepository;
import com.chattergame.item.Storage;
import com.chattergame.item.StorageRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Валидация готовности `DRAFT → READY` (`REQUIREMENTS §20`, `D-112`).
 *
 * <p>На `E03` это был каркас: проверки опирались на таблицы, которых ещё нет, и включались
 * вместе со своими этапами. `E04` и `E06` свои части не сделали, и `D-112` отдал валидацию
 * `E07` целиком — иначе готовность объявлялась бы по неполному своду правил.
 *
 * <p>Разделение на блокирующие и предупреждающие замечания — не оформление: блокирующие
 * запрещают переход, предупреждающие не мешают, потому что флаг `READY` остаётся за мастером.
 * Отчёт не бросает исключение: мастеру нужно увидеть все замечания сразу, а не по одному
 * за запрос.
 *
 * <p>Проверка «радиочат менее чем с двумя участниками» сюда не входит: признак `RADIO`
 * в `chat.kind` есть, а списка участников радио в схеме нет — он появляется на `E11`
 * (`REQUIREMENTS §7.2`), и выдумывать его здесь запрещено (`D-112`).
 */
@Component
public class ReadinessValidation {

    private final ChatRepository chats;
    private final TransitionRepository transitions;
    private final CharacterRepository characters;
    private final PresenceRepository presence;
    private final StorageRepository storages;
    private final ItemRepository items;

    public ReadinessValidation(
            ChatRepository chats,
            TransitionRepository transitions,
            CharacterRepository characters,
            PresenceRepository presence,
            StorageRepository storages,
            ItemRepository items) {
        this.chats = chats;
        this.transitions = transitions;
        this.characters = characters;
        this.presence = presence;
        this.storages = storages;
        this.items = items;
    }

    /** Проверки готовности для игры. Пустые списки вместо `null` (`CONVENTIONS`). */
    public ReadinessReport validate(UUID gameId) {
        List<ReadinessReport.Finding> blocking = new ArrayList<>();
        List<ReadinessReport.Finding> warning = new ArrayList<>();

        List<Chat> locations = chats.findByGame(gameId, ChatKind.LOCATION).stream()
                .filter(chat -> !chat.archived())
                .toList();
        List<Character> alive = characters.findByGame(gameId).stream()
                .filter(character -> !character.archived())
                .toList();
        Set<UUID> placedCharacters = new HashSet<>();
        for (Presence placement : presence.findActiveByGame(gameId)) {
            placedCharacters.add(placement.characterId());
        }

        // Блокирующее: играбельный персонаж без размещения — игрок вошёл бы в игру и не нашёл
        // себя нигде. NPC проверку не проходит: он не слот, и место ему выбирает мастер.
        for (Character character : alive) {
            if (character.playable() && !placedCharacters.contains(character.id())) {
                blocking.add(new ReadinessReport.Finding(
                        "character_without_placement",
                        "Играбельный персонаж «" + character.name() + "» нигде не размещён"));
            }
        }

        // Блокирующее: предмет вне хранилища. При инварианте «предмет всегда ровно в одном
        // хранилище» это проверка целостности, а не ошибки ввода: она ловит последствие сбоя.
        for (UUID itemId : items.itemsWithoutPlacement(gameId)) {
            blocking.add(new ReadinessReport.Finding(
                    "item_without_storage", "Предмет " + itemId + " не лежит ни в одном хранилище"));
        }

        // Блокирующее: переход ведёт не в локацию. Внешний ключ не даёт сослаться на
        // несуществующую строку, но не мешает связать локацию с радиочатом или личным чатом.
        for (Transition transition : transitions.findByGame(gameId)) {
            if (transition.archived()) {
                continue;
            }
            for (UUID end : List.of(transition.fromChatId(), transition.toChatId())) {
                if (chats.findById(end).filter(chat -> chat.kind() == ChatKind.LOCATION).isEmpty()) {
                    blocking.add(new ReadinessReport.Finding(
                            "transition_to_non_location",
                            "Переход " + transition.id() + " ведёт в чат, который не является локацией"));
                }
            }
        }

        // Предупреждающее: локация без описания — играть можно, но карточка места пуста.
        for (Chat location : locations) {
            if (location.description() == null || location.description().isBlank()) {
                warning.add(new ReadinessReport.Finding(
                        "location_without_description",
                        "У локации «" + location.title() + "» нет описания"));
            }
        }

        // Предупреждающее: персонаж без портрета — мир читается хуже, но играть можно.
        for (Character character : alive) {
            if (character.portraitAssetId() == null) {
                warning.add(new ReadinessReport.Finding(
                        "character_without_portrait",
                        "У персонажа «" + character.name() + "» нет портрета"));
            }
        }

        // Предупреждающее: локация без единого перехода при непустом графе — в неё не войти
        // и из неё не выйти. Прочтение «недостижимая локация» названо в `STATUS.md`: другого
        // определения в документах нет, а обход от «начала» построить не из чего — начальной
        // локации в модели не существует (`REQUIREMENTS §8`).
        Set<UUID> linked = new HashSet<>();
        for (Transition transition : transitions.findByGame(gameId)) {
            linked.add(transition.fromChatId());
            linked.add(transition.toChatId());
        }
        if (locations.size() > 1) {
            for (Chat location : locations) {
                if (!linked.contains(location.id())) {
                    warning.add(new ReadinessReport.Finding(
                            "unreachable_location",
                            "Локация «" + location.title() + "» не связана ни одним переходом"));
                }
            }
        }

        // Предупреждающее: NPC вне мира — нигде не находится и ничего не держит, то есть
        // заведён и забыт. Локация не в счёт: у локации владельца-персонажа нет вовсе.
        Set<UUID> ownersWithStorage = new HashSet<>();
        for (Storage storage : storages.findByGame(gameId)) {
            if (storage.characterId() != null) {
                ownersWithStorage.add(storage.characterId());
            }
        }
        for (Character character : alive) {
            if (character.npc()
                    && !placedCharacters.contains(character.id())
                    && !ownersWithStorage.contains(character.id())) {
                warning.add(new ReadinessReport.Finding(
                        "npc_outside_world",
                        "NPC «" + character.name() + "» нигде не находится и ничего не держит"));
            }
        }

        return new ReadinessReport(blocking, warning);
    }

    /** Готовность возможна и при предупреждениях: флаг остаётся за мастером (`REQUIREMENTS §20`). */
    public boolean blocksReady(ReadinessReport report) {
        return !report.canBecomeReady();
    }

    public List<ReadinessReport.Finding> all(ReadinessReport report) {
        return Stream.concat(report.blocking().stream(), report.warning().stream()).toList();
    }
}
