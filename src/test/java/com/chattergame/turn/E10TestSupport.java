package com.chattergame.turn;

import com.chattergame.character.Character;
import com.chattergame.game.Game;
import com.chattergame.game.GameMutationService;
import com.chattergame.game.GameTransition;
import com.chattergame.game.MembershipRole;
import com.chattergame.game.MembershipStatus;
import com.chattergame.message.MessageService;
import com.chattergame.user.E02TestSupport;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Общая обвязка тестов `E10`: живая игра с локацией, персонажем игрока и мастером.
 *
 * <p>Игра доводится до `RUNNING` тем же путём, что и на `E06`–`E08`: локация, персонаж,
 * размещение, занятие. Занятие выставлено прямо в базе — эндпоинт занятия принадлежит
 * `E13` (`D-107`), а лимит хода проверяется для персонажа, которым кто-то играет; без
 * владельца расход просто не с кем связать.
 *
 * <p>Второй чат заводится прямым `INSERT`: радиочат — предмет `E11`, и его создание в
 * `E10` было бы кодом не своего этапа. Для суммарного лимита (`D-013`) важен не способ
 * появления чата, а то, что чат другой.
 */
abstract class E10TestSupport extends E02TestSupport {

    @Autowired
    protected GameMutationService mutations;

    @Autowired
    protected MessageService writes;

    @Autowired
    protected TurnService turns;

    @Autowired
    protected TurnRepository turnRepository;

    /** Игра со всем нужным: мастер, локация, персонаж игрока и второй игрок по запросу. */
    protected final class Fixture {

        final UUID gameId;
        final UUID masterUserId;
        final UUID locationId;
        final Character character;
        final UUID playerUserId;
        final UUID playerMembershipId;
        final String playerToken;

        private Fixture(
                UUID gameId,
                UUID masterUserId,
                UUID locationId,
                Character character,
                UUID playerUserId,
                UUID playerMembershipId,
                String playerToken) {
            this.gameId = gameId;
            this.masterUserId = masterUserId;
            this.locationId = locationId;
            this.character = character;
            this.playerUserId = playerUserId;
            this.playerMembershipId = playerMembershipId;
            this.playerToken = playerToken;
        }

        /** Вторая локация: бюджет суммарный по чатам, и проверяется он на двух разных. */
        UUID secondLocation(String title) {
            return mutations.createLocation(masterUserId, gameId, title, null).id();
        }

        /** Чат другого вида. Радио и шёпот заводит `E11`, здесь нужен просто второй чат. */
        UUID chatOfKind(String kind, String title) {
            UUID chatId = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO chat (id, game_id, kind, title) VALUES (?, ?, ?, ?)",
                    chatId, gameId, kind, title);
            return chatId;
        }

        void place(UUID characterId, UUID chatId) {
            mutations.placeCharacter(masterUserId, gameId, characterId, chatId);
        }

        /**
         * Присутствие в чате, который не локация. Радиочат и личный чат заводит `E11`,
         * а присутствие в них через перемещение недоступно: `placeCharacter` принимает
         * только локацию. Прямая запись в `presence` — та же обвязка, что занятие на
         * `E06`–`E08`: правило принадлежит другому этапу, а здесь проверяется расход.
         */
        void presenceIn(UUID chatId) {
            jdbc.update("UPDATE presence SET left_at = now() WHERE character_id = ? AND left_at IS NULL",
                    character.id());
            jdbc.update(
                    "INSERT INTO presence (id, character_id, chat_id, hidden, joined_at) "
                            + "VALUES (?, ?, ?, false, now())",
                    UUID.randomUUID(), character.id(), chatId);
        }

        Turn openTurn() {
            return mutations.openTurn(masterUserId, gameId);
        }

        Turn closeTurn() {
            return mutations.closeTurn(masterUserId, gameId);
        }

        void setTurnBased(boolean value) {
            mutations.setTurnBased(masterUserId, gameId, value);
        }

        boolean turnBased() {
            Boolean value = jdbc.queryForObject(
                    "SELECT turn_based FROM game WHERE id = ?", Boolean.class, gameId);
            return Boolean.TRUE.equals(value);
        }
    }

    /** Игра в `RUNNING` с локацией и персонажем, за которого играет отдельный игрок. */
    protected Fixture turnGame(boolean turnBased) throws Exception {
        String masterLogin = unique("МастерХода");
        registerAndGetToken(masterLogin, PASSWORD);
        UUID masterUserId = jdbc.queryForObject(
                "SELECT id FROM app_user WHERE login_normalized = ?",
                UUID.class,
                loginNormalized(masterLogin));

        Game game = mutations.createGame(masterUserId, "Ход " + unique("E10"), null, null);
        UUID locationId = mutations.createLocation(masterUserId, game.id(), "Площадь", null).id();
        mutations.applyTransition(masterUserId, game.id(), GameTransition.MARK_READY, null);
        mutations.applyTransition(masterUserId, game.id(), GameTransition.START, null);

        Character character = mutations.createCharacter(
                masterUserId, game.id(), "Ходок-" + unique(""), null, null, null, null, true, false);
        mutations.placeCharacter(masterUserId, game.id(), character.id(), locationId);

        String playerLogin = unique("ИгрокХода");
        register(playerLogin, OTHER_PASSWORD, null);
        UUID playerUserId = jdbc.queryForObject(
                "SELECT id FROM app_user WHERE login_normalized = ?",
                UUID.class,
                loginNormalized(playerLogin));
        UUID membershipId = mutations.addMembership(
                masterUserId, game.id(), playerUserId, MembershipRole.PLAYER, MembershipStatus.JOINED).id();
        jdbc.update("UPDATE character SET occupant_membership_id = ? WHERE id = ?",
                membershipId, character.id());

        if (turnBased) {
            mutations.setTurnBased(masterUserId, game.id(), true);
        }

        return new Fixture(
                game.id(),
                masterUserId,
                locationId,
                character,
                playerUserId,
                membershipId,
                loginAndGetToken(playerLogin, OTHER_PASSWORD));
    }

    /** Наблюдатель игры: читает, но бюджета не имеет вовсе (`D-063`). */
    protected String observerToken(Fixture fixture) throws Exception {
        String login = unique("НаблюдательХода");
        register(login, OTHER_PASSWORD, null);
        UUID userId = jdbc.queryForObject(
                "SELECT id FROM app_user WHERE login_normalized = ?",
                UUID.class,
                loginNormalized(login));
        mutations.addMembership(
                fixture.masterUserId, fixture.gameId, userId,
                MembershipRole.OBSERVER, MembershipStatus.JOINED);
        return loginAndGetToken(login, OTHER_PASSWORD);
    }

    protected List<String> eventTypes(UUID gameId) {
        return jdbc.queryForList(
                "SELECT type FROM game_event WHERE game_id = ? ORDER BY seq", String.class, gameId);
    }

    protected long countEvents(UUID gameId, String type) {
        return countRows(
                "SELECT count(*) FROM game_event WHERE game_id = ? AND type = ?", gameId, type);
    }

    protected Map<String, Object> payloadOf(UUID gameId, long seq) {
        String text = jdbc.queryForObject(
                "SELECT payload::text FROM game_event WHERE game_id = ? AND seq = ?",
                String.class, gameId, seq);
        try {
            return json.readValue(text, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("payload события не разобран: " + text, e);
        }
    }

    protected List<TurnBudget> budgetsOf(Turn turn) {
        return turnRepository.findBudgets(turn.id());
    }

    /** Строки бюджета всей игры: нужны там, где важно, заведены ли они вообще. */
    protected long budgetsOfGame(UUID gameId) {
        return countRows(
                "SELECT count(*) FROM turn_budget b JOIN turn t ON t.id = b.turn_id WHERE t.game_id = ?",
                gameId);
    }

    protected void assertNoTurnRows(UUID gameId) {
        assertThat(countRows("SELECT count(*) FROM turn WHERE game_id = ?", gameId))
                .as("ход не открывался: строк нет")
                .isZero();
    }
}
