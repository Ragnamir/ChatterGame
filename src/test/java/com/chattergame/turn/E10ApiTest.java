package com.chattergame.turn;

import com.chattergame.message.MessageKind;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Счётчик хода в API (`PLAN` E10, `F03`).
 *
 * <p>Проверяется то, чего не видно в сервисных тестах: что счётчик читается маршрутом,
 * что чужая игра не отвечает, и что расход сужен под смотрящего — мастер видит всех,
 * игрок только своих, наблюдатель никого (`D-063`).
 *
 * <p>Отдельно проверяется `PATCH`: `turnBased` необязательное поле, и его отсутствие в
 * запросе обязано означать «не трогать», иначе правка названия выключала бы режим.
 */
class E10ApiTest extends E10TestSupport {

    @Test
    void counterIsReadableByTheMasterAndStartsClosed() throws Exception {
        Fixture fixture = turnGame(false);

        MvcResult result = call(get("/games/" + fixture.gameId + "/turn"), masterToken(fixture), null);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode turn = json.readTree(bodyOf(result));
        assertThat(turn.path("turnBased").asBoolean()).isFalse();
        assertThat(turn.path("open").asBoolean()).isFalse();
        assertThat(turn.path("n").asLong())
                .as("ход ещё не открывали: номер ноль, а не отсутствие поля")
                .isZero();
        assertThat(turn.path("budgets")).isEmpty();
    }

    @Test
    void openAndCloseAreVisibleThroughTheApi() throws Exception {
        Fixture fixture = turnGame(true);
        String master = masterToken(fixture);

        MvcResult opened = call(post("/games/" + fixture.gameId + "/turn/open"), master, Map.of());

        assertThat(opened.getResponse().getStatus()).isEqualTo(200);
        JsonNode open = json.readTree(bodyOf(opened));
        assertThat(open.path("open").asBoolean()).isTrue();
        assertThat(open.path("n").asLong()).isEqualTo(1L);
        assertThat(open.path("openedAt").isNull()).isFalse();

        MvcResult closed = call(post("/games/" + fixture.gameId + "/turn/close"), master, Map.of());

        assertThat(closed.getResponse().getStatus()).isEqualTo(200);
        JsonNode close = json.readTree(bodyOf(closed));
        assertThat(close.path("open").asBoolean()).isFalse();
        assertThat(close.path("n").asLong()).isEqualTo(1L);
        assertThat(close.path("closedAt").isNull()).isFalse();
    }

    @Test
    void refusedOpenCarriesTheMachineCode() throws Exception {
        Fixture fixture = turnGame(false);

        MvcResult refused = call(
                post("/games/" + fixture.gameId + "/turn/open"), masterToken(fixture), Map.of());

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(bodyOf(refused)).path("code").asText())
                .isEqualTo("turn_based_off");
    }

    /** `D-063`: наблюдатель читает игру, но бюджета не имеет — и ничьего расхода не видит. */
    @Test
    void theObserverSeesNoBudgetAtAll() throws Exception {
        Fixture fixture = turnGame(true);
        fixture.openTurn();
        writes.post(fixture.playerUserId, fixture.gameId, fixture.locationId, fixture.character.id(),
                MessageKind.SPEECH, "речь игрока", false);

        MvcResult master = call(get("/games/" + fixture.gameId + "/turn"), masterToken(fixture), null);
        MvcResult player = call(get("/games/" + fixture.gameId + "/turn"), fixture.playerToken, null);
        MvcResult observer = call(
                get("/games/" + fixture.gameId + "/turn"), observerToken(fixture), null);

        assertThat(json.readTree(bodyOf(master)).path("budgets"))
                .as("мастер видит расход всех")
                .hasSize(1);
        assertThat(json.readTree(bodyOf(player)).path("budgets"))
                .as("игрок видит расход своего персонажа")
                .hasSize(1);
        assertThat(json.readTree(bodyOf(observer)).path("budgets"))
                .as("наблюдателю расход не показывается")
                .isEmpty();
    }

    @Test
    void anotherGameIsNotReadable() throws Exception {
        Fixture fixture = turnGame(true);
        Fixture stranger = turnGame(true);

        MvcResult result = call(
                get("/games/" + fixture.gameId + "/turn"), masterToken(stranger), null);

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
    }

    /**
     * `PATCH` с посторонним полем режим не трогает: `turnBased` отсутствует, значит «не
     * трогать». Обратное поведение выключило бы пошаговый режим при правке заголовка.
     */
    @Test
    void patchWithoutTheFlagLeavesTheModeAlone() throws Exception {
        Fixture fixture = turnGame(true);

        MvcResult result = call(
                patch("/games/" + fixture.gameId), masterToken(fixture),
                Map.of("title", "Новое название"));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(bodyOf(result)).path("turnBased").asBoolean())
                .as("отсутствие поля — «не трогать»")
                .isTrue();
        assertThat(json.readTree(bodyOf(result)).path("title").asText())
                .isEqualTo("Новое название");
    }

    @Test
    void patchTurnsTheModeOnAndOff() throws Exception {
        Fixture fixture = turnGame(false);
        String master = masterToken(fixture);

        MvcResult on = call(patch("/games/" + fixture.gameId), master, Map.of("turnBased", true));

        assertThat(on.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(bodyOf(on)).path("turnBased").asBoolean()).isTrue();

        MvcResult off = call(patch("/games/" + fixture.gameId), master, Map.of("turnBased", false));

        assertThat(off.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(bodyOf(off)).path("turnBased").asBoolean()).isFalse();
        assertThat(countEvents(fixture.gameId, "TURN_OPENED")).isZero();
    }

    @Test
    void onlyTheMasterChangesTheFlag() throws Exception {
        Fixture fixture = turnGame(false);

        MvcResult refused = call(
                patch("/games/" + fixture.gameId), fixture.playerToken, Map.of("turnBased", true));

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(fixture.turnBased()).isFalse();
    }

    /** Токен мастера: обвязка заводит мастера, но не хранит его сессию. */
    private String masterToken(Fixture fixture) throws Exception {
        String login = jdbc.queryForObject(
                "SELECT login FROM app_user WHERE id = ?", String.class, fixture.masterUserId);
        return loginAndGetToken(login, PASSWORD);
    }
}
