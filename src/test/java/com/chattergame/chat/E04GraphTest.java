package com.chattergame.chat;

import com.chattergame.game.GameMutationService;
import com.chattergame.game.MembershipRole;
import com.chattergame.game.MembershipStatus;
import com.chattergame.user.E02TestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Граф локаций, переходов и знания рёбер (`PLAN` E04, задачи 2–6).
 *
 * <p>Тесты идут через HTTP, потому что предмет этапа — не хранение, а видимость: один и
 * тот же граф обязан отличаться для мастера, для игрока и для двух игроков между собой.
 * Проверка через сервис пропустила бы ровно эту часть.
 */
class E04GraphTest extends E02TestSupport {

    @Autowired
    private GameMutationService mutations;

    @Test
    void locationMutationLeavesOneEventPerChange() throws Exception {
        Fixture fixture = masterWithGame();
        UUID location = fixture.location("Таверна");

        assertThat(eventTypes(fixture.gameId()))
                .as("создание локации — мутация мира, значит обязано оставить событие (D-033)")
                .containsExactly("GAME_CREATED", "CHAT_CREATED");

        assertThat(call(post("/games/" + fixture.gameId() + "/locations/" + location + "/archive"),
                        fixture.token(), Map.of("reason", "сгорела"))
                        .getResponse().getStatus())
                .isEqualTo(200);
        assertThat(eventTypes(fixture.gameId()))
                .containsExactly("GAME_CREATED", "CHAT_CREATED", "CHAT_ARCHIVED");
    }

    /** Архивация идемпотентна: повтор не пишет второе событие и не трогает первую причину. */
    @Test
    void archivingIsIdempotentAndKeepsTheRow() throws Exception {
        Fixture fixture = masterWithGame();
        UUID location = fixture.location("Подвал");

        call(post("/games/" + fixture.gameId() + "/locations/" + location + "/archive"),
                fixture.token(), Map.of("reason", "затопило"));
        call(post("/games/" + fixture.gameId() + "/locations/" + location + "/archive"),
                fixture.token(), Map.of("reason", "затопило второй раз"));

        assertThat(countRows("SELECT count(*) FROM chat WHERE id = ?", location))
                .as("локация не удаляется никогда (D-010)")
                .isEqualTo(1);
        assertThat(countRows(
                        "SELECT count(*) FROM game_event WHERE game_id = ? AND type = 'CHAT_ARCHIVED'",
                        fixture.gameId()))
                .as("повторная архивация не оставляет второе событие")
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT archived_reason FROM chat WHERE id = ?", String.class, location))
                .isEqualTo("затопило");
    }

    /** Критерий приёмки `E04`: `DELETE` отвечает ошибкой с указанием причины до и после `READY`. */
    @Test
    void deletingALocationIsRefusedBeforeAndAfterReady() throws Exception {
        Fixture fixture = masterWithGame();
        UUID location = fixture.location("Чердак");

        MvcResult before = call(
                delete("/games/" + fixture.gameId() + "/locations/" + location), fixture.token(), null);
        assertThat(before.getResponse().getStatus()).isEqualTo(409);
        assertThat(readField(before, "code")).isEqualTo("chat_not_deletable");
        assertThat(readField(before, "detail"))
                .as("до READY отказ подсказывает архивацию, а не молчит общей ошибкой")
                .contains("архивац");

        fixture.start();

        MvcResult after = call(
                delete("/games/" + fixture.gameId() + "/locations/" + location), fixture.token(), null);
        assertThat(after.getResponse().getStatus())
                .as("после READY удаление тоже запрещено (D-010, D-103)")
                .isEqualTo(409);
        assertThat(readField(after, "code")).isEqualTo("chat_not_deletable");
        assertThat(countRows("SELECT count(*) FROM chat WHERE id = ?", location))
                .as("отказ не трогает строку")
                .isEqualTo(1);
    }

    /** Критерий приёмки `E04`: `DELETE` перехода отвечает отказом с причиной. */
    @Test
    void deletingATransitionIsRefused() throws Exception {
        Fixture fixture = masterWithGame();
        UUID from = fixture.location("Зал");
        UUID to = fixture.location("Коридор");
        UUID transition = fixture.transition(from, to, "в коридор");

        fixture.start();

        MvcResult refused = call(
                delete("/games/" + fixture.gameId() + "/graph/transitions/" + transition),
                fixture.token(), null);
        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(readField(refused, "code")).isEqualTo("transition_not_deletable");
        assertThat(countRows("SELECT count(*) FROM transition WHERE id = ?", transition))
                .isEqualTo(1);
        assertThat(eventTypes(fixture.gameId()))
                .as("отказ — не мутация мира")
                .doesNotContain("TRANSITION_ARCHIVED");
    }

    /**
     * Критерий приёмки `E04`: состояние ребра привязано к персонажу. Ребро одно, знания
     * разные — тест ловит привязку к переходу, к игре или к игроку вместо персонажа
     * (`REQUIREMENTS §8`).
     */
    @Test
    void edgeStateIsStoredPerCharacterNotPerEdge() throws Exception {
        Fixture fixture = masterWithGame();
        UUID from = fixture.location("Площадь");
        UUID to = fixture.location("Храм");
        UUID transition = fixture.transition(from, to, "к храму");
        UUID knows = fixture.character("Аня");
        UUID doesNotKnow = fixture.character("Борис");

        assertThat(call(put("/games/" + fixture.gameId() + "/graph/transitions/" + transition + "/state"),
                        fixture.token(), Map.of(
                                "characterId", knows.toString(),
                                "state", "KNOWN_UNAVAILABLE",
                                "reason", "ворота заперты"))
                        .getResponse().getStatus())
                .isEqualTo(200);
        assertThat(call(put("/games/" + fixture.gameId() + "/graph/transitions/" + transition + "/state"),
                        fixture.token(), Map.of(
                                "characterId", doesNotKnow.toString(),
                                "state", "AVAILABLE"))
                        .getResponse().getStatus())
                .isEqualTo(200);

        assertThat(countRows(
                        "SELECT count(*) FROM transition_state WHERE transition_id = ?", transition))
                .as("у одного ребра два состояния: по одному на персонажа")
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "SELECT state FROM transition_state WHERE transition_id = ? AND character_id = ?",
                        String.class, transition, knows))
                .isEqualTo("KNOWN_UNAVAILABLE");
    }

    /** `KNOWN_UNAVAILABLE` без причины — это молчание вместо ответа игроку (`D-103`). */
    @Test
    void unavailableEdgeRequiresReason() throws Exception {
        Fixture fixture = masterWithGame();
        UUID from = fixture.location("Ворота");
        UUID to = fixture.location("Лес");
        UUID transition = fixture.transition(from, to, null);
        UUID character = fixture.character("Вера");

        MvcResult refused = call(
                put("/games/" + fixture.gameId() + "/graph/transitions/" + transition + "/state"),
                fixture.token(),
                Map.of("characterId", character.toString(), "state", "KNOWN_UNAVAILABLE"));

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(readField(refused, "code")).isEqualTo("edge_reason_required");
        assertThat(countRows(
                        "SELECT count(*) FROM transition_state WHERE transition_id = ?", transition))
                .as("отказ не оставляет состояния")
                .isZero();
    }

    /** Критерий приёмки: граф клиента не содержит рёбер в архивную локацию. */
    @Test
    void playerGraphHasNoEdgesIntoArchivedLocations() throws Exception {
        Fixture fixture = masterWithGame();
        UUID hall = fixture.location("Холл");
        UUID cellar = fixture.location("Погреб");
        UUID transition = fixture.transition(hall, cellar, "вниз");
        UUID character = fixture.character("Гриша");
        UUID player = fixture.addPlayer(fixture.gameId(), character);

        fixture.start();
        call(post("/games/" + fixture.gameId() + "/locations/" + cellar + "/archive"),
                fixture.token(), Map.of("reason", "обвалился"));
        call(put("/games/" + fixture.gameId() + "/graph/transitions/" + transition + "/state"),
                fixture.token(), Map.of("characterId", character.toString(), "state", "AVAILABLE"));

        JsonNode graph = readJson(call(get("/games/" + fixture.gameId() + "/graph")
                .queryParam("asCharacter", character.toString()), fixture.tokenFor(player), null));

        assertThat(graph.at("/edges"))
                .as("ребро в архивную локацию игроку не отдаётся")
                .isEmpty();
        assertThat(graph.at("/nodes").toString())
                .as("архивная локация показана как выведенная из игры, а не исчезла (D-009)")
                .contains("Погреб")
                .contains("\"archived\":true")
                .contains("обвалился");
    }

    /** Критерий приёмки `E04`: архивация до `READY` игроку не видна, после — видна как «выведена». */
    @Test
    void archivedLocationIsInvisibleBeforeReadyAndMarkedAfter() throws Exception {
        Fixture fixture = masterWithGame();
        fixture.location("Живая");
        UUID cancelled = fixture.location("Отменённая");
        UUID character = fixture.character("Даша");
        UUID player = fixture.addPlayer(fixture.gameId(), character);
        String playerToken = fixture.tokenFor(player);

        call(post("/games/" + fixture.gameId() + "/locations/" + cancelled + "/archive"),
                fixture.token(), Map.of("reason", "передумал"));

        JsonNode beforeReady = readJson(call(get("/games/" + fixture.gameId() + "/graph")
                .queryParam("asCharacter", character.toString()), playerToken, null));
        assertThat(beforeReady.at("/nodes").toString())
                .as("до READY игрок не должен знать, что мастер что-то заводил и передумал (D-009)")
                .doesNotContain("Отменённая")
                .contains("Живая");

        fixture.start();

        JsonNode afterReady = readJson(call(get("/games/" + fixture.gameId() + "/graph")
                .queryParam("asCharacter", character.toString()), playerToken, null));
        assertThat(afterReady.at("/nodes").toString())
                .as("после READY архивная локация видна с причиной")
                .contains("Отменённая")
                .contains("\"archived\":true")
                .contains("передумал");
    }

    /** Ребро, которого персонаж не знает, в ответ не попадает: прятать его на клиенте поздно. */
    @Test
    void unknownEdgesAreNotSentToPlayers() throws Exception {
        Fixture fixture = masterWithGame();
        UUID hall = fixture.location("Прихожая");
        UUID secret = fixture.location("Тайник");
        UUID edge = fixture.transition(hall, secret, "потайная дверь");
        UUID character = fixture.character("Егор");
        UUID player = fixture.addPlayer(fixture.gameId(), character);

        fixture.start();
        call(put("/games/" + fixture.gameId() + "/graph/transitions/" + edge + "/state"),
                fixture.token(), Map.of("characterId", character.toString(), "state", "UNKNOWN"));

        JsonNode graph = readJson(call(get("/games/" + fixture.gameId() + "/graph")
                .queryParam("asCharacter", character.toString()), fixture.tokenFor(player), null));
        assertThat(graph.at("/edges").toString())
                .as("состояние UNKNOWN и есть «не знаю»: ребро не отдаётся даже как недоступное")
                .doesNotContain(edge.toString());
    }

    /** Игрок смотрит только своим персонажем: иначе он вычитал бы знание соседа (`D-103`). */
    @Test
    void playerCannotLookThroughAnotherPersonsEyes() throws Exception {
        Fixture fixture = masterWithGame();
        UUID own = fixture.character("Женя");
        UUID other = fixture.character("Зина");
        UUID player = fixture.addPlayer(fixture.gameId(), own);
        fixture.start();

        MvcResult refused = call(get("/games/" + fixture.gameId() + "/graph")
                .queryParam("asCharacter", other.toString()), fixture.tokenFor(player), null);

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(readField(refused, "code")).isEqualTo("not_your_character");
    }

    /** Мастер без `asCharacter` видит полный граф, включая рёбра, скрытые от игроков. */
    @Test
    void masterSeesTheWholeGraphIncludingArchivedEdges() throws Exception {
        Fixture fixture = masterWithGame();
        UUID from = fixture.location("Кабинет");
        UUID to = fixture.location("Библиотека");
        UUID transition = fixture.transition(from, to, "в библиотеку");

        call(post("/games/" + fixture.gameId() + "/graph/transitions/" + transition + "/archive"),
                fixture.token(), Map.of("reason", "проход замурован"));

        JsonNode graph = readJson(call(get("/games/" + fixture.gameId() + "/graph"),
                fixture.token(), null));

        assertThat(graph.at("/edges").toString())
                .as("мастер управляет миром: архивное ребро остаётся у него на виду")
                .contains(transition.toString())
                .contains("\"archived\":true")
                .contains("проход замурован");
        assertThat(countRows("SELECT count(*) FROM transition WHERE id = ?", transition))
                .as("архивация не удаляет ребро (D-010)")
                .isEqualTo(1);
    }

    /** Переход из локации в саму себя — не переход: клиент показал бы кнопку в никуда. */
    @Test
    void selfTransitionIsRefused() throws Exception {
        Fixture fixture = masterWithGame();
        UUID location = fixture.location("Одинокая");

        MvcResult refused = call(post("/games/" + fixture.gameId() + "/graph/transitions"),
                fixture.token(), Map.of(
                        "fromChatId", location.toString(),
                        "toChatId", location.toString()));

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(readField(refused, "code")).isEqualTo("transition_same_location");
    }

    /** После `FINISHED` мир не правится (`D-094`); архивация остаётся — она закрывает, а не меняет. */
    @Test
    void finishedGameRefusesWorldEditsButStillAllowsArchiving() throws Exception {
        Fixture fixture = masterWithGame();
        UUID location = fixture.location("Финал");
        fixture.start();
        call(post("/games/" + fixture.gameId() + "/transitions"), fixture.token(),
                Map.of("transition", "FINISH"));

        MvcResult refused = call(post("/games/" + fixture.gameId() + "/locations"),
                fixture.token(), Map.of("title", "Правка задним числом"));
        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(readField(refused, "code")).isEqualTo("content_change_after_finish");

        assertThat(call(post("/games/" + fixture.gameId() + "/locations/" + location + "/archive"),
                        fixture.token(), Map.of("reason", "игра закрыта"))
                        .getResponse().getStatus())
                .as("архивация — способ закрыть мир, а не изменить его (D-103)")
                .isEqualTo(200);
    }

    /** Не мастер не строит мир, даже если игра ещё черновик. */
    @Test
    void nonMasterCannotBuildTheWorld() throws Exception {
        Fixture fixture = masterWithGame();
        UUID character = fixture.character("Иван");
        UUID player = fixture.addPlayer(fixture.gameId(), character);

        MvcResult refused = call(post("/games/" + fixture.gameId() + "/locations"),
                fixture.tokenFor(player), Map.of("title", "Самозваная локация"));

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(countRows(
                        "SELECT count(*) FROM chat WHERE game_id = ? AND kind = 'LOCATION'",
                        fixture.gameId()))
                .as("права проверяются на каждое событие, а не один раз за соединение (D-065)")
                .isZero();
    }

    /** Локация без названия не создаётся: пустой узел графа клиенту нечего показать. */
    @Test
    void locationWithoutTitleIsRefused() throws Exception {
        Fixture fixture = masterWithGame();

        MvcResult refused = call(post("/games/" + fixture.gameId() + "/locations"),
                fixture.token(), Map.of("title", "   "));

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(eventTypes(fixture.gameId()))
                .as("отказ не оставляет события о локации, которой нет")
                .containsExactly("GAME_CREATED");
    }

    // --- обвязка -----------------------------------------------------------------------

    private List<String> eventTypes(UUID gameId) {
        return jdbc.queryForList(
                "SELECT type FROM game_event WHERE game_id = ? ORDER BY seq", String.class, gameId);
    }

    private String readField(MvcResult result, String field) throws Exception {
        return json.readTree(bodyOf(result)).get(field).asText();
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return json.readTree(bodyOf(result));
    }

    /** Игра мастера со средствами строить мир: локации, переходы, персонажи, игроки. */
    private final class Fixture {

        private final UUID gameId;
        private final UUID userId;
        private final String token;

        private Fixture(UUID gameId, UUID userId, String token) {
            this.gameId = gameId;
            this.userId = userId;
            this.token = token;
        }

        private UUID gameId() {
            return gameId;
        }

        private String token() {
            return token;
        }

        private UUID location(String title) throws Exception {
            MvcResult created = call(post("/games/" + gameId + "/locations"), token,
                    Map.of("title", title, "description", "описание " + title));
            assertThat(created.getResponse().getStatus()).isEqualTo(201);
            return UUID.fromString(readField(created, "id"));
        }

        private UUID transition(UUID from, UUID to, String title) throws Exception {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("fromChatId", from.toString());
            payload.put("toChatId", to.toString());
            if (title != null) {
                payload.put("title", title);
            }
            MvcResult created = call(post("/games/" + gameId + "/graph/transitions"), token, payload);
            assertThat(created.getResponse().getStatus()).isEqualTo(201);
            return UUID.fromString(readField(created, "id"));
        }

        private UUID character(String name) throws Exception {
            MvcResult created = call(post("/games/" + gameId + "/characters"), token,
                    Map.of("name", name));
            assertThat(created.getResponse().getStatus()).isEqualTo(201);
            return UUID.fromString(readField(created, "id"));
        }

        /**
         * Игрок с закреплённым персонажем. Занятие — предмет `E06`; здесь оно ставится
         * прямым `UPDATE`, потому что проверка «чьими глазами» читает колонку, а заполнять
         * её на `E04` нечем (`D-103`).
         */
        private UUID addPlayer(UUID game, UUID characterId) throws Exception {
            String login = unique("Игрок");
            register(login, OTHER_PASSWORD, null);
            UUID playerId = jdbc.queryForObject(
                    "SELECT id FROM app_user WHERE login_normalized = ?",
                    UUID.class,
                    loginNormalized(login));
            mutations.addMembership(
                    userId, game, playerId, MembershipRole.PLAYER, MembershipStatus.JOINED);
            jdbc.update(
                    "UPDATE character SET occupant_membership_id = "
                            + "(SELECT id FROM membership WHERE game_id = ? AND user_id = ?) "
                            + "WHERE id = ?",
                    game,
                    playerId,
                    characterId);
            return playerId;
        }

        private String tokenFor(UUID playerId) throws Exception {
            String login = jdbc.queryForObject(
                    "SELECT login FROM app_user WHERE id = ?", String.class, playerId);
            return loginAndGetToken(login, OTHER_PASSWORD);
        }

        private void start() throws Exception {
            call(post("/games/" + gameId + "/transitions"), token, Map.of("transition", "MARK_READY"));
            call(post("/games/" + gameId + "/transitions"), token, Map.of("transition", "START"));
        }
    }

    private Fixture masterWithGame() throws Exception {
        String login = unique("Мастер");
        String token = registerAndGetToken(login, PASSWORD);
        MvcResult created = call(post("/games"), token, Map.of("title", "Игра " + unique("М")));
        UUID gameId = UUID.fromString(readField(created, "id"));
        UUID userId = jdbc.queryForObject(
                "SELECT id FROM app_user WHERE login_normalized = ?", UUID.class, loginNormalized(login));
        return new Fixture(gameId, userId, token);
    }
}
