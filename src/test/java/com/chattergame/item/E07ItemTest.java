package com.chattergame.item;

import com.chattergame.game.Game;
import com.chattergame.game.GameMutationService;
import com.chattergame.game.ReadinessReport;
import com.chattergame.game.ReadinessValidation;
import com.chattergame.game.MembershipRole;
import com.chattergame.game.MembershipStatus;
import com.chattergame.user.E02TestSupport;
import com.chattergame.util.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Хранилища и предметы (`PLAN` E07).
 *
 * <p>Тесты идут через сервисы, а не через HTTP. Отклонение от обвязки `E04`–`E06`
 * осознанное: предмет этапа — гонка за строкой в Postgres и материализованный инвариант
 * «предмета вне хранилища не бывает», а `MockMvc` для проверки гонки не даёт ничего, зато
 * прячет настоящий пул соединений. Критерий `PLAN` прямо требует реальный Postgres и пул.
 */
class E07ItemTest extends E02TestSupport {

    @Autowired
    private GameMutationService mutations;

    @Autowired
    private StorageQueryService queries;

    @Autowired
    private ItemRepository items;

    @Autowired
    private ReadinessValidation readiness;

    // --- Вместимость и словарь ---------------------------------------------------------

    /** `D-111`: размер решает, в какое из двух хранилищ персонажа предмет помещается. */
    @Test
    void sizeDecidesWhichCharacterStorageAcceptsTheItem() {
        assertThat(PlacementRules.fits(StorageKind.CHARACTER_HIDDEN, ItemSize.SMALL)).isTrue();
        assertThat(PlacementRules.fits(StorageKind.CHARACTER_HIDDEN, ItemSize.LARGE)).isFalse();
        assertThat(PlacementRules.fits(StorageKind.CHARACTER_VISIBLE, ItemSize.LARGE)).isTrue();
        assertThat(PlacementRules.fits(StorageKind.CHARACTER_VISIBLE, ItemSize.SMALL)).isFalse();
        assertThat(PlacementRules.fits(StorageKind.LOCATION, ItemSize.LARGE))
                .as("на локационные хранилища размер не влияет")
                .isTrue();
        assertThat(PlacementRules.fits(StorageKind.CHEST, ItemSize.SMALL)).isTrue();
    }

    /** Механику складывают в стопку, клинок — нет (`D-114`). */
    @Test
    void onlyCountableTypesAcceptQuantityAboveOne() {
        Fixture fixture = masterWithGame();
        UUID chest = fixture.chest();

        assertThat(mutations.createItem(fixture.userId(), fixture.gameId(), ItemType.MONEY,
                        "Монеты", null, null, ItemSize.SMALL, 25, null, chest).quantity())
                .isEqualTo(25);
        assertThat(mutations.createItem(fixture.userId(), fixture.gameId(), ItemType.MISC,
                        "Верёвка", null, null, ItemSize.SMALL, null, null, chest).quantity())
                .as("количество по умолчанию — единица")
                .isEqualTo(1);

        for (int quantity : new int[] {2, 5}) {
            int amount = quantity;
            assertThatThrownBy(() -> mutations.createItem(fixture.userId(), fixture.gameId(),
                            ItemType.WEAPON, "Клинок", null, null, ItemSize.SMALL, amount, null, chest))
                    .isInstanceOf(ApiException.class)
                    .hasFieldOrPropertyWithValue("code", "item_not_countable");
        }
        assertThatThrownBy(() -> mutations.createItem(fixture.userId(), fixture.gameId(),
                        ItemType.MONEY, "Монеты", null, null, ItemSize.SMALL, 0, null, chest))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "quantity_invalid");
    }

    /**
     * Ноль единиц не должен появиться даже при прямой записи в базу: ограничение количества
     * стоит в миграции, а не только в сервисе.
     */
    @Test
    void databaseRefusesZeroQuantityDirectly() {
        Fixture fixture = masterWithGame();

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO item (id, game_id, type, name, size, quantity) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), fixture.gameId(), "MONEY", "Ноль монет", "SMALL", 0))
                .hasMessageContaining("chk_item_quantity_positive");
    }

    // --- Хранилища ---------------------------------------------------------------------

    /** `REQUIREMENTS §10`: у персонажа по одному хранилищу каждого типа, у локации — сколько угодно. */
    @Test
    void characterHasOneStoragePerKindWhileLocationMayHaveMany() {
        Fixture fixture = masterWithGame();
        UUID hall = fixture.location("Зал");
        UUID character = fixture.character("Иван");

        mutations.createStorage(fixture.userId(), fixture.gameId(),
                StorageKind.CHARACTER_HIDDEN, null, character, "За пазухой", null);
        mutations.createStorage(fixture.userId(), fixture.gameId(),
                StorageKind.CHARACTER_VISIBLE, null, character, "За спиной", null);

        assertThatThrownBy(() -> mutations.createStorage(fixture.userId(), fixture.gameId(),
                        StorageKind.CHARACTER_HIDDEN, null, character, "Второе", null))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "storage_already_exists");

        mutations.createStorage(fixture.userId(), fixture.gameId(),
                StorageKind.CHEST, hall, null, "Сундук", null);
        mutations.createStorage(fixture.userId(), fixture.gameId(),
                StorageKind.CHEST, hall, null, "Второй сундук", null);

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM storage WHERE character_id = ?", Long.class, character))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM storage WHERE chat_id = ?", Long.class, hall))
                .isEqualTo(2);
    }

    /** Хранилище персонажа без персонажа и локационное без локации не создаются. */
    @Test
    void storageRequiresItsOwnerAndRefusesTheOtherKindOfOwner() {
        Fixture fixture = masterWithGame();
        UUID hall = fixture.location("Зал");
        UUID character = fixture.character("Иван");

        assertThatThrownBy(() -> mutations.createStorage(fixture.userId(), fixture.gameId(),
                        StorageKind.CHARACTER_HIDDEN, null, null, "Ничья", null))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "storage_character_required");
        assertThatThrownBy(() -> mutations.createStorage(fixture.userId(), fixture.gameId(),
                        StorageKind.CHEST, null, null, "Ничей", null))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "storage_chat_required");
        assertThatThrownBy(() -> mutations.createStorage(fixture.userId(), fixture.gameId(),
                        StorageKind.CHEST, hall, character, "Смешанный", null))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "storage_owner_conflict");
        assertThatThrownBy(() -> mutations.createStorage(fixture.userId(), fixture.gameId(),
                        StorageKind.CHARACTER_VISIBLE, hall, character, "Смешанный", null))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "storage_owner_conflict");
    }

    /** Открытие и закрытие — своя мутация: появившееся хранилище не бывает сразу открытым. */
    @Test
    void storageAccessIsItsOwnMutationAndIsIdempotent() {
        Fixture fixture = masterWithGame();
        UUID chest = fixture.chest();

        assertThat(mutations.setStorageOpen(fixture.userId(), fixture.gameId(), chest, false).open())
                .as("повторная установка того же значения не пишет событие")
                .isFalse();
        assertThat(countEvents(fixture.gameId(), "STORAGE_ACCESS_CHANGED")).isZero();

        assertThat(mutations.setStorageOpen(fixture.userId(), fixture.gameId(), chest, true).open())
                .isTrue();
        assertThat(countEvents(fixture.gameId(), "STORAGE_ACCESS_CHANGED")).isEqualTo(1);

        assertThat(mutations.setStorageOpen(fixture.userId(), fixture.gameId(), chest, false).open())
                .isFalse();
        assertThat(countEvents(fixture.gameId(), "STORAGE_ACCESS_CHANGED")).isEqualTo(2);
    }

    // --- Инвариант размещения ----------------------------------------------------------

    /**
     * Предмета вне хранилища не бывает ни после создания, ни после неудачного перемещения
     * (`D-015`): отказ проигравшему в гонке не оставляет предмет без хранилища.
     */
    @Test
    void itemIsAlwaysInExactlyOneStorage() {
        Fixture fixture = masterWithGame();
        UUID chest = fixture.chest();
        UUID other = fixture.chest();
        Item item = fixture.item(chest, ItemType.MONEY, ItemSize.SMALL, 5);

        assertThat(placements(item.id())).isEqualTo(1);

        mutations.moveItem(fixture.userId(), fixture.gameId(), item.id(), chest, other, null);
        assertThat(placements(item.id())).isEqualTo(1);

        assertThatThrownBy(() -> mutations.moveItem(
                        fixture.userId(), fixture.gameId(), item.id(), chest, other, null))
                .as("источник устарел: предмет уже уехал из сундука")
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "item_already_taken");

        assertThat(items.itemsWithoutPlacement(fixture.gameId()))
                .as("инвариант «предмет всегда в хранилище» проверяется одним запросом")
                .isEmpty();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM item_placement WHERE item_id = ?", Long.class, item.id()))
                .isEqualTo(1);
    }

    // --- Перемещение и стопки ----------------------------------------------------------

    /** Полное взятие переезжает `UPDATE`-ом: строка та же, событие одно (`D-033`). */
    @Test
    void movingWholeItemKeepsTheSameRowAndLeavesOneEvent() {
        Fixture fixture = masterWithGame();
        UUID chest = fixture.chest();
        UUID bag = fixture.hiddenStorageFor(fixture.character("Иван"));
        Item item = fixture.item(chest, ItemType.KEY, ItemSize.SMALL, 1);

        MoveResult moved = mutations.moveItem(
                fixture.userId(), fixture.gameId(), item.id(), chest, bag, null);

        assertThat(moved.item().id()).isEqualTo(item.id());
        assertThat(moved.to().id()).isEqualTo(bag);
        assertThat(moved.from().id()).isEqualTo(chest);
        assertThat(countEvents(fixture.gameId(), "ITEM_MOVED")).isEqualTo(1);
        assertThat(storageOf(item.id())).isEqualTo(bag);
    }

    /**
     * `D-114`: часть стопки отделяется новой строкой, остаток не обнуляется и не удаляется.
     * Отсюда и `newItemId` в payload — иначе по журналу не восстановить, что появилось.
     */
    @Test
    void takingPartOfAStackSplitsItIntoTwoRows() {
        Fixture fixture = masterWithGame();
        UUID chest = fixture.chest();
        UUID bag = fixture.hiddenStorageFor(fixture.character("Иван"));
        Item stack = fixture.item(chest, ItemType.MONEY, ItemSize.SMALL, 10);

        MoveResult moved = mutations.moveItem(
                fixture.userId(), fixture.gameId(), stack.id(), chest, bag, 3);

        assertThat(moved.item().id()).isNotEqualTo(stack.id());
        assertThat(moved.item().quantity()).isEqualTo(3);
        assertThat(items.findById(stack.id()).orElseThrow().quantity()).isEqualTo(7);
        assertThat(storageOf(stack.id())).isEqualTo(chest);
        assertThat(storageOf(moved.item().id())).isEqualTo(bag);
        assertThat(countEvents(fixture.gameId(), "ITEM_MOVED")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT payload ->> 'newItemId' FROM game_event "
                                + "WHERE game_id = ? AND type = 'ITEM_MOVED'",
                        String.class,
                        fixture.gameId()))
                .isEqualTo(moved.item().id().toString());
    }

    /** Взять больше, чем лежит в стопке, нельзя; из неисчисляемого нельзя взять часть. */
    @Test
    void takingMoreThanTheStackOrPartOfAnIndivisibleItemIsRefused() {
        Fixture fixture = masterWithGame();
        UUID chest = fixture.chest();
        UUID bag = fixture.hiddenStorageFor(fixture.character("Иван"));
        Item stack = fixture.item(chest, ItemType.MONEY, ItemSize.SMALL, 4);

        assertThatThrownBy(() -> mutations.moveItem(
                        fixture.userId(), fixture.gameId(), stack.id(), chest, bag, 5))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "quantity_too_large");
        assertThatThrownBy(() -> mutations.moveItem(
                        fixture.userId(), fixture.gameId(), stack.id(), chest, bag, 0))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "quantity_invalid");
        assertThat(items.findById(stack.id()).orElseThrow().quantity())
                .as("отказ не меняет стопку")
                .isEqualTo(4);
    }

    /** Крупный предмет в мелкое хранилище не помещается (`D-111`). */
    @Test
    void largeItemDoesNotFitIntoSmallStorage() {
        Fixture fixture = masterWithGame();
        UUID chest = fixture.chest();
        UUID bag = fixture.hiddenStorageFor(fixture.character("Иван"));
        Item blade = fixture.item(chest, ItemType.WEAPON, ItemSize.LARGE, 1);

        assertThatThrownBy(() -> mutations.moveItem(
                        fixture.userId(), fixture.gameId(), blade.id(), chest, bag, null))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "item_does_not_fit");
        assertThat(storageOf(blade.id()))
                .as("неудачное перемещение не оставляет предмет вне хранилища")
                .isEqualTo(chest);
    }

    /**
     * Критерий приёмки `E07`: двадцать параллельных попыток забрать один предмет из сундука
     * дают ровно один успех, остальные получают отказ «предмет уже забран», и предмет
     * оказывается у одного владельца.
     *
     * <p>Проверяется исход гонки, а не доставка системного события проигравшему: событие —
     * это сообщение, и его доставкой владеет `E08`/`E09` (`D-113`).
     */
    @Test
    void twentyParallelAttemptsGiveExactlyOneWinner() throws Exception {
        Fixture fixture = masterWithGame();
        UUID chest = fixture.chest();
        Item item = fixture.item(chest, ItemType.KEY, ItemSize.SMALL, 1);

        int attempts = 20;
        List<UUID> targets = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            targets.add(fixture.hiddenStorageFor(fixture.character("Вор " + i)));
        }
        List<String> codes = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger successes = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        try {
            for (int i = 0; i < attempts; i++) {
                UUID target = targets.get(i);
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        mutations.moveItem(
                                fixture.userId(), fixture.gameId(), item.id(), chest, target, null);
                        successes.incrementAndGet();
                    } catch (ApiException e) {
                        codes.add(e.code());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(successes.get()).as("победитель ровно один").isEqualTo(1);
        assertThat(codes).as("все проигравшие получили отказ «уже забран»").hasSize(attempts - 1);
        assertThat(codes).allSatisfy(code -> assertThat(code).isEqualTo("item_already_taken"));
        assertThat(storageOf(item.id())).as("предмет у одного владельца").isIn(targets);
        assertThat(countEvents(fixture.gameId(), "ITEM_MOVED"))
                .as("переезд в журнале один: девятнадцать отказов не меняли мир")
                .isEqualTo(1);
        assertThat(items.itemsWithoutPlacement(fixture.gameId())).isEmpty();
    }

    // --- Видимость ---------------------------------------------------------------------

    /** `D-019`/`D-017`: скрытый владелец не показывает своего хранилища, мастер видит всё. */
    @Test
    void hiddenOwnerDoesNotShowStorageWhileMasterSeesEverything() {
        Fixture fixture = masterWithGame();
        UUID hall = fixture.location("Зал");
        UUID owner = fixture.character("Иван");
        UUID ownerStorage = fixture.visibleStorageFor(owner);
        Item blade = fixture.item(ownerStorage, ItemType.WEAPON, ItemSize.LARGE, 1);
        fixture.place(owner, hall);
        mutations.setCharacterStealth(fixture.userId(), fixture.gameId(), owner, true, "ушёл в тень");

        Viewer player = fixture.viewerIn(hall);

        assertThat(queries.ofCharacter(player.userId(), fixture.gameId(), owner))
                .as("скрытый владелец не показывает своего хранилища")
                .isEmpty();

        List<StorageQueryService.VisibleStorage> seenByMaster =
                queries.ofCharacter(fixture.userId(), fixture.gameId(), owner);
        assertThat(seenByMaster).hasSize(1);
        assertThat(seenByMaster.getFirst().items())
                .as("мастер видит крупное оружие скрытого персонажа")
                .extracting(StorageQueryService.VisibleItem::id)
                .containsExactly(blade.id());
    }

    /** Мелкий предмет в видимом хранилище чужому не показывается даже у видимого владельца. */
    @Test
    void smallItemIsNotShownToOthersEvenWhenOwnerIsVisible() {
        Fixture fixture = masterWithGame();
        UUID hall = fixture.location("Зал");
        UUID owner = fixture.character("Иван");
        UUID ownerStorage = fixture.visibleStorageFor(owner);
        Item blade = fixture.item(ownerStorage, ItemType.WEAPON, ItemSize.LARGE, 1);
        Item coin = fixture.item(ownerStorage, ItemType.MONEY, ItemSize.SMALL, 10);
        fixture.place(owner, hall);
        Viewer player = fixture.viewerIn(hall);

        List<StorageQueryService.VisibleStorage> seen =
                queries.ofCharacter(player.userId(), fixture.gameId(), owner);

        assertThat(seen).hasSize(1);
        assertThat(seen.getFirst().items())
                .extracting(StorageQueryService.VisibleItem::id)
                .as("видно крупное, не видно мелкого")
                .containsExactly(blade.id())
                .doesNotContain(coin.id());
    }

    /** Сундук: описание видно присутствующим, содержимое — после открытия мастером. */
    @Test
    void chestContentsAreHiddenUntilTheMasterOpensIt() {
        Fixture fixture = masterWithGame();
        UUID hall = fixture.location("Зал");
        UUID chest = fixture.chestIn(hall, "Сундук у стены");
        Item treasure = fixture.item(chest, ItemType.VALUABLE, ItemSize.LARGE, 1);
        Viewer player = fixture.viewerIn(hall);

        List<StorageQueryService.VisibleStorage> closed =
                queries.inChat(player.userId(), fixture.gameId(), hall);

        assertThat(closed).hasSize(1);
        assertThat(closed.getFirst().contentsVisible()).isFalse();
        assertThat(closed.getFirst().items()).isEmpty();
        assertThat(closed.getFirst().title()).as("сам сундук виден").isEqualTo("Сундук у стены");

        mutations.setStorageOpen(fixture.userId(), fixture.gameId(), chest, true);

        List<StorageQueryService.VisibleStorage> opened =
                queries.inChat(player.userId(), fixture.gameId(), hall);
        assertThat(opened.getFirst().contentsVisible()).isTrue();
        assertThat(opened.getFirst().items())
                .extracting(StorageQueryService.VisibleItem::id)
                .containsExactly(treasure.id());
    }

    /** Содержимое хранилища локации видно присутствующим в ней, а не всем игрокам игры. */
    @Test
    void locationStorageIsVisibleOnlyToThosePresentInTheLocation() {
        Fixture fixture = masterWithGame();
        UUID hall = fixture.location("Зал");
        UUID yard = fixture.location("Двор");
        UUID hallStorage = fixture.storageIn(hall, StorageKind.LOCATION, "Запас зала");
        fixture.item(hallStorage, ItemType.TOOL, ItemSize.SMALL, 1);

        Viewer inside = fixture.viewerIn(hall);
        Viewer outside = fixture.viewerIn(yard);

        assertThat(queries.inChat(inside.userId(), fixture.gameId(), hall))
                .as("присутствующий видит содержимое хранилища локации")
                .hasSize(1);
        assertThat(queries.inChat(outside.userId(), fixture.gameId(), hall))
                .as("не присутствующий не видит ничего, включая само хранилище")
                .isEmpty();
    }

    /** Игрок не ведёт мир (`D-065`, `D-109`): любая запись о хранилищах и предметах — отказ. */
    @Test
    void playerCannotWriteStoragesOrItems() {
        Fixture fixture = masterWithGame();
        UUID chest = fixture.chest();
        Item item = fixture.item(chest, ItemType.MISC, ItemSize.SMALL, 1);
        UUID player = fixture.playerUserId();
        UUID hall = fixture.location("Мимо");
        List<String> before = eventTypes(fixture.gameId());

        assertThatThrownBy(() -> mutations.createStorage(player, fixture.gameId(),
                        StorageKind.CHEST, hall, null, "Мой сундук", null))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "not_master");
        assertThatThrownBy(() -> mutations.createItem(player, fixture.gameId(), ItemType.MISC,
                        "Самозванец", null, null, ItemSize.SMALL, 1, null, chest))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "not_master");
        assertThatThrownBy(() -> mutations.moveItem(
                        player, fixture.gameId(), item.id(), chest, chest, null))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "not_master");

        assertThat(eventTypes(fixture.gameId()))
                .as("отказы не оставляют событий о мире")
                .isEqualTo(before);
    }

    // --- Валидация готовности (D-112) --------------------------------------------------

    /** Блокирующее: играбельный персонаж без размещения останавливает `DRAFT → READY`. */
    @Test
    void readyIsBlockedByUnplacedPlayableCharacter() {
        Fixture fixture = masterWithGame();
        UUID hall = fixture.location("Зал");
        UUID character = fixture.character("Иван");

        assertThat(codes(readiness.validate(fixture.gameId()).blocking()))
                .contains("character_without_placement");

        fixture.place(character, hall);

        assertThat(readiness.validate(fixture.gameId()).blocking())
                .as("после размещения блокирующих замечаний нет")
                .isEmpty();
        assertThat(readiness.validate(fixture.gameId()).warning())
                .as("предупреждения остались: локация с описанием, а портрета нет")
                .isNotEmpty();
    }

    /** Предупреждения не мешают готовности: флаг `READY` за мастером (`REQUIREMENTS §20`). */
    @Test
    void warningsDoNotBlockReady() {
        Fixture fixture = masterWithGame();
        UUID bare = mutations.createLocation(fixture.userId(), fixture.gameId(), "Пустая", null).id();
        assertThat(bare).isNotNull();

        ReadinessReport report = readiness.validate(fixture.gameId());

        assertThat(codes(report.warning())).contains("location_without_description");
        assertThat(report.canBecomeReady())
                .as("предупреждение не запрещает объявить готовность")
                .isTrue();
    }

    /** Блокирующее: предмет вне хранилища — нарушение инварианта, а не ошибка мастера. */
    @Test
    void itemOutsideStorageBlocksReady() {
        Fixture fixture = masterWithGame();
        UUID chest = fixture.chest();
        Item item = fixture.item(chest, ItemType.MISC, ItemSize.SMALL, 1);

        jdbc.update("DELETE FROM item_placement WHERE item_id = ?", item.id());

        assertThat(codes(readiness.validate(fixture.gameId()).blocking()))
                .contains("item_without_storage");
    }

    /** Предупреждающее: заведённый и забытый NPC не держит мир. */
    @Test
    void npcOutsideWorldIsAWarning() {
        Fixture fixture = masterWithGame();
        mutations.createCharacter(
                fixture.userId(), fixture.gameId(), "Забытый", null, null, null, null, false, true);

        assertThat(codes(readiness.validate(fixture.gameId()).warning()))
                .contains("npc_outside_world");
    }

    /** Предупреждающее: локация без переходов недостижима при непустом графе. */
    @Test
    void locationWithoutTransitionsIsAWarning() {
        Fixture fixture = masterWithGame();
        fixture.location("Зал");
        fixture.location("Подвал");

        assertThat(codes(readiness.validate(fixture.gameId()).warning()))
                .contains("unreachable_location");
    }

    // --- Веб-слой ----------------------------------------------------------------------

    /** Мастер заводит хранилище и предмет через API, а игрок получает отказ (`D-109`). */
    @Test
    void masterCreatesStorageAndItemThroughApiWhilePlayerIsRefused() throws Exception {
        Fixture fixture = masterWithGame();
        UUID hall = fixture.location("Зал");

        MvcResult created = call(post("/games/" + fixture.gameId() + "/storages"), fixture.token(),
                Map.of("kind", "CHEST", "chatId", hall.toString(), "title", "Сундук"));
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        UUID chest = UUID.fromString(readJson(created).get("id").asText());

        MvcResult item = call(post("/games/" + fixture.gameId() + "/items"), fixture.token(),
                Map.of("type", "MONEY", "name", "Монеты", "size", "SMALL", "quantity", 12,
                        "storageId", chest.toString()));
        assertThat(item.getResponse().getStatus()).isEqualTo(201);
        assertThat(readJson(item).get("quantity").asInt()).isEqualTo(12);

        Viewer player = fixture.viewerIn(hall);
        MvcResult refused = call(post("/games/" + fixture.gameId() + "/storages"), player.token(),
                Map.of("kind", "CHEST", "chatId", hall.toString(), "title", "Мой сундук"));
        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(readJson(refused).get("code").asText()).isEqualTo("not_master");
    }

    /** Гонка через HTTP: второе перемещение того же предмета получает `409 item_already_taken`. */
    @Test
    void secondMoveThroughApiReportsItemAlreadyTaken() throws Exception {
        Fixture fixture = masterWithGame();
        UUID chest = fixture.chest();
        UUID first = fixture.chest();
        UUID second = fixture.chest();
        Item item = fixture.item(chest, ItemType.KEY, ItemSize.SMALL, 1);

        MvcResult moved = call(post("/games/" + fixture.gameId() + "/items/" + item.id() + "/move"),
                fixture.token(),
                Map.of("fromStorageId", chest.toString(), "storageId", first.toString()));
        assertThat(moved.getResponse().getStatus()).isEqualTo(200);

        MvcResult again = call(post("/games/" + fixture.gameId() + "/items/" + item.id() + "/move"),
                fixture.token(),
                Map.of("fromStorageId", chest.toString(), "storageId", second.toString()));
        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        assertThat(readJson(again).get("code").asText()).isEqualTo("item_already_taken");
    }

    /** Игрок не видит хранилищ отсутствующего персонажа, а мастер видит их в локации. */
    @Test
    void playerSeesNothingForAbsentCharacterWhileMasterSeesTheChest() throws Exception {
        Fixture fixture = masterWithGame();
        UUID hall = fixture.location("Зал");
        UUID chest = fixture.chestIn(hall, "Сундук у стены");
        fixture.item(chest, ItemType.VALUABLE, ItemSize.LARGE, 1);
        Viewer player = fixture.viewerIn(hall);

        JsonNode forPlayer = readJson(call(
                get("/games/" + fixture.gameId() + "/locations/" + hall + "/storages"),
                player.token(),
                null));
        assertThat(forPlayer).hasSize(1);
        assertThat(forPlayer.get(0).get("contentsVisible").asBoolean())
                .as("закрытый сундук не показывает содержимого")
                .isFalse();

        mutations.setStorageOpen(fixture.userId(), fixture.gameId(), chest, true);
        JsonNode opened = readJson(call(
                get("/games/" + fixture.gameId() + "/locations/" + hall + "/storages"),
                player.token(),
                null));
        assertThat(opened.get(0).get("items")).hasSize(1);
    }

    private static List<String> codes(List<ReadinessReport.Finding> findings) {
        return findings.stream().map(ReadinessReport.Finding::code).toList();
    }

    // --- обвязка -----------------------------------------------------------------------

    private JsonNode readJson(MvcResult result) throws Exception {
        return json.readTree(bodyOf(result));
    }

    private int placements(UUID itemId) {
        return jdbc.queryForObject(
                        "SELECT count(*) FROM item_placement WHERE item_id = ?", Integer.class, itemId);
    }

    private UUID storageOf(UUID itemId) {
        return jdbc.queryForObject(
                "SELECT storage_id FROM item_placement WHERE item_id = ?", UUID.class, itemId);
    }

    private int countEvents(UUID gameId, String type) {
        return jdbc.queryForObject(
                        "SELECT count(*) FROM game_event WHERE game_id = ? AND type = ?",
                        Integer.class,
                        gameId,
                        type);
    }

    private List<String> eventTypes(UUID gameId) {
        return jdbc.queryForList(
                "SELECT type FROM game_event WHERE game_id = ? ORDER BY seq", String.class, gameId);
    }

    private record Viewer(UUID userId, String token) {
    }

    private record PlayerSession(UUID userId, String token) {
    }

    /** Игра мастера со средствами строить мир: локации, персонажи, хранилища и предметы. */
    private final class Fixture {

        private final Game game;
        private final UUID masterId;
        private final String token;

        private Fixture(Game game, UUID masterId, String token) {
            this.game = game;
            this.masterId = masterId;
            this.token = token;
        }

        private String token() {
            return token;
        }

        private UUID gameId() {
            return game.id();
        }

        private UUID userId() {
            return masterId;
        }

        private UUID location(String title) {
            return mutations.createLocation(masterId, gameId(), title, "описание " + title).id();
        }

        private UUID character(String name) {
            return mutations.createCharacter(
                    masterId, gameId(), name, null, null, null, null, true, false).id();
        }

        private UUID chest() {
            return chestIn(location("Зал " + unique("L")), "Сундук");
        }

        private UUID chestIn(UUID chatId, String title) {
            return storage(StorageKind.CHEST, chatId, null, title);
        }

        private UUID storageIn(UUID chatId, StorageKind kind, String title) {
            return storage(kind, chatId, null, title);
        }

        private UUID hiddenStorageFor(UUID characterId) {
            return storage(StorageKind.CHARACTER_HIDDEN, null, characterId, "За пазухой");
        }

        private UUID visibleStorageFor(UUID characterId) {
            return storage(StorageKind.CHARACTER_VISIBLE, null, characterId, "За спиной");
        }

        private UUID storage(StorageKind kind, UUID chatId, UUID characterId, String title) {
            return mutations.createStorage(masterId, gameId(), kind, chatId, characterId, title, null).id();
        }

        private Item item(UUID storageId, ItemType type, ItemSize size, int quantity) {
            return mutations.createItem(masterId, gameId(), type, "Предмет " + unique("П"),
                    null, null, size, quantity, null, storageId);
        }

        private void place(UUID characterId, UUID chatId) {
            mutations.placeCharacter(masterId, gameId(), characterId, chatId);
        }

        /** Участие `PLAYER` в этой игре: новый пользователь каждый раз, иначе повторный вход запрещён. */
        private UUID playerUserId() {
            return playerSession().userId();
        }

        private PlayerSession playerSession() {
            String login = unique("Игрок");
            String playerToken;
            try {
                playerToken = registerAndGetToken(login, OTHER_PASSWORD);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            UUID playerId = jdbc.queryForObject(
                    "SELECT id FROM app_user WHERE login_normalized = ?",
                    UUID.class,
                    loginNormalized(login));
            mutations.addMembership(masterId, gameId(), playerId, MembershipRole.PLAYER, MembershipStatus.JOINED);
            return new PlayerSession(playerId, playerToken);
        }

        /** Смотрящий игрок: своё участие, свой персонаж в нужной локации и занятость (`D-100`). */
        private Viewer viewerIn(UUID chatId) {
            PlayerSession session = playerSession();
            UUID character = character("Наблюдатель " + unique("N"));
            place(character, chatId);
            UUID membershipId = jdbc.queryForObject(
                    "SELECT id FROM membership WHERE game_id = ? AND user_id = ?",
                    UUID.class,
                    gameId(),
                    session.userId());
            jdbc.update(
                    "UPDATE character SET occupant_membership_id = ? WHERE id = ?", membershipId, character);
            return new Viewer(session.userId(), session.token());
        }
    }

    private Fixture masterWithGame() {
        String login = unique("Мастер");
        String token;
        try {
            token = registerAndGetToken(login, PASSWORD);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        UUID masterId = jdbc.queryForObject(
                "SELECT id FROM app_user WHERE login_normalized = ?", UUID.class, loginNormalized(login));
        Game game = mutations.createGame(masterId, "Игра " + unique("М"), "Снаружи", "Внутри");
        return new Fixture(game, masterId, token);
    }
}
