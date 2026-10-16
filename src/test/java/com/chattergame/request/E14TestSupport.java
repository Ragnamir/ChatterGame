package com.chattergame.request;

import com.chattergame.character.Character;
import com.chattergame.game.Game;
import com.chattergame.game.GameMutationService;
import com.chattergame.game.GameTransition;
import com.chattergame.game.Membership;
import com.chattergame.game.MembershipRepository;
import com.chattergame.game.Visibility;
import com.chattergame.item.Storage;
import com.chattergame.item.StorageKind;
import com.chattergame.user.E02TestSupport;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Общая обвязка тестов `E14`: живая игра, мастер, игрок, локация с переходом, персонаж
 * и хранилище. Всё заводится сервисом, а не SQL: заявка правит готовый мир, и собирать
 * этот мир руками значило бы проверять не тот этап.
 *
 * <p>Переход нужен не для полноты: `move_character` перемещает по ребру, и откат
 * перемещения обязан вернуть персонажа в прежнюю локацию. Без второй локации и ребра
 * между ними критерий «откат применяет старые значения» проверить нечем.
 */
abstract class E14TestSupport extends E02TestSupport {

    @Autowired
    protected GameMutationService mutations;

    @Autowired
    protected MembershipRepository memberships;

    @Autowired
    protected RequestService requests;

    protected final class Account {
        final UUID userId;
        final String login;
        final String token;

        private Account(UUID userId, String login, String token) {
            this.userId = userId;
            this.login = login;
            this.token = token;
        }
    }

    protected Account account(String prefix) throws Exception {
        String login = unique(prefix);
        register(login, PASSWORD, null);
        UUID userId = jdbc.queryForObject(
                "SELECT id FROM app_user WHERE login_normalized = ?",
                UUID.class,
                loginNormalized(login));
        return new Account(userId, login, loginAndGetToken(login, PASSWORD));
    }

    /**
     * Мир заявок: мастер, игрок, две локации с ребром, персонаж в первой и хранилище там же.
     * Игра доводится до `RUNNING`: `READY` нужен, чтобы в неё мог войти игрок (`D-132`).
     */
    protected final class Fixture {
        final Account master;
        final Account player;
        final UUID gameId;
        final UUID place;
        final UUID elsewhere;
        final UUID transitionId;
        final UUID characterId;
        final UUID storageId;

        private Fixture(
                Account master,
                Account player,
                UUID gameId,
                UUID place,
                UUID elsewhere,
                UUID transitionId,
                UUID characterId,
                UUID storageId) {
            this.master = master;
            this.player = player;
            this.gameId = gameId;
            this.place = place;
            this.elsewhere = elsewhere;
            this.transitionId = transitionId;
            this.characterId = characterId;
            this.storageId = storageId;
        }

        String fieldOf(String column) {
            return jdbc.queryForObject(
                    "SELECT " + column + " FROM character WHERE id = ?", String.class, characterId);
        }

        UUID locationOf() {
            return jdbc.queryForObject(
                    "SELECT chat_id FROM presence WHERE character_id = ? AND left_at IS NULL",
                    UUID.class,
                    characterId);
        }

        String storageTitle() {
            return jdbc.queryForObject(
                    "SELECT title FROM storage WHERE id = ?", String.class, storageId);
        }

        long itemCount() {
            return countRows(
                    "SELECT count(*) FROM item WHERE game_id = ? AND name LIKE 'Заявка-%'", gameId);
        }

        String roleplayWishesOf(UUID userId) {
            return jdbc.queryForObject(
                    "SELECT roleplay_wishes FROM membership WHERE game_id = ? AND user_id = ?",
                    String.class,
                    gameId,
                    userId);
        }
    }

    protected Fixture fixture() throws Exception {
        Account master = account("МастерЗаявок");
        Game game = mutations.createGame(master.userId, "Игра заявок " + unique(""), null, null);
        UUID place = mutations.createLocation(master.userId, game.id(), "Площадь", "Описание").id();
        UUID elsewhere = mutations.createLocation(master.userId, game.id(), "Причал", "Описание").id();
        UUID transition = mutations.createTransition(
                        master.userId, game.id(), place, elsewhere, "Мостик")
                .id();
        Character character = mutations.createCharacter(
                master.userId, game.id(), "Искатель-" + unique(""), null, "высокий", "открыто",
                "закрыто", true, false);
        mutations.placeCharacter(master.userId, game.id(), character.id(), place);
        Storage storage = mutations.createStorage(
                master.userId, game.id(), StorageKind.CHEST, place, null, "Сундук", "Пусто");

        mutations.changeVisibility(master.userId, game.id(), Visibility.PUBLIC);
        mutations.applyTransition(master.userId, game.id(), GameTransition.MARK_READY, null);
        mutations.applyTransition(master.userId, game.id(), GameTransition.START, null);

        Account player = account("ИгрокЗаявок");
        mutations.joinOpenGame(player.userId, game.id());
        return new Fixture(
                master, player, game.id(), place, elsewhere, transition, character.id(), storage.id());
    }

    /** Подача заявки: `origin` выводится из автора, а не задаётся тестом вслух. */
    protected GameRequest submit(
            Fixture fixture, Account author, RequestType type, Map<String, Object> payload) {
        return requests.submit(
                author.userId,
                fixture.gameId,
                new RequestService.Submission(
                        author.userId.equals(fixture.master.userId)
                                ? RequestOrigin.MASTER
                                : RequestOrigin.PLAYER,
                        type,
                        null,
                        payload,
                        null,
                        null));
    }

    protected Membership membershipOf(Fixture fixture, Account account) {
        return memberships.find(fixture.gameId, account.userId).orElseThrow();
    }

    /** Правка внешности заявкой: единственная операция, у которой откат точный и проверяемый. */
    protected static Map<String, Object> fieldOperation(UUID characterId, String value) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("characterId", characterId.toString());
        payload.put("field", "appearance");
        payload.put("value", value);
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("op", "update_character_field");
        operation.put("payload", payload);
        return operation;
    }

    protected static Map<String, Object> itemOperation(UUID storageId, String name) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("storageId", storageId.toString());
        payload.put("name", name);
        payload.put("size", "SMALL");
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("op", "create_item");
        operation.put("payload", payload);
        return operation;
    }

    protected static Map<String, Object> moveOperation(UUID characterId, UUID transitionId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("characterId", characterId.toString());
        payload.put("transitionId", transitionId.toString());
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("op", "move_character");
        operation.put("payload", payload);
        return operation;
    }

    protected static Map<String, Object> changeSet(Map<String, Object>... operations) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operations", List.of(operations));
        return payload;
    }

    protected String statusOf(UUID requestId) {
        return jdbc.queryForObject(
                "SELECT status FROM request WHERE id = ?", String.class, requestId);
    }

    protected String operationStatus(UUID requestId, int ordinal) {
        return jdbc.queryForObject(
                "SELECT status FROM request_operation WHERE request_id = ? AND ordinal = ?",
                String.class,
                requestId,
                ordinal);
    }

    protected long eventCount(UUID gameId, String type) {
        return countRows(
                "SELECT count(*) FROM game_event WHERE game_id = ? AND type = ?", gameId, type);
    }
}
