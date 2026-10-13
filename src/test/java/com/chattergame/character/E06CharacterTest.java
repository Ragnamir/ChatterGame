package com.chattergame.character;

import com.chattergame.game.GameMutationService;
import com.chattergame.game.MembershipRole;
import com.chattergame.game.MembershipStatus;
import com.chattergame.user.E02TestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Персонажи, присутствие, скрытность и перемещение (`PLAN` E06).
 *
 * <p>Тесты идут через HTTP, потому что предмет этапа — видимость: одна и та же карточка и
 * один и тот же граф обязаны отличаться для мастера и для игрока. Проверка через сервис
 * пропустила бы ровно эту часть, как и на `E04`.
 *
 * <p>Занятие персонажа игроком сюда не входит: решением `D-102` им владеет `E13`, поэтому
 * на `E06` все записи ведёт мастер, а отсутствие права проверяется отказом.
 */
class E06CharacterTest extends E02TestSupport {

    @Autowired
    private GameMutationService mutations;

    // --- Карточка ----------------------------------------------------------------------

    /** Критерий приёмки: карточка пишется целиком и оставляет одно событие на мутацию (`D-033`). */
    @Test
    void cardIsStoredWholeAndLeavesOneEvent() throws Exception {
        Fixture fixture = masterWithGame();

        MvcResult created = call(post("/games/" + fixture.gameId() + "/characters"),
                fixture.token(), card("Иван", "Высокий, в плаще", "Купец из Твери", "Боится воды"));

        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = readJson(created);
        assertThat(body.get("name").asText()).isEqualTo("Иван");
        assertThat(body.get("appearance").asText()).isEqualTo("Высокий, в плаще");
        assertThat(body.get("publicHistory").asText()).isEqualTo("Купец из Твери");
        assertThat(eventTypes(fixture.gameId()))
                .as("персонаж заводится мимо журнала — это уже было дефектом E04 (D-033)")
                .containsExactly("GAME_CREATED", "CHARACTER_CREATED");
    }

    /** `REQUIREMENTS §9`: закрытая история — только играющему игроку и мастеру. */
    @Test
    void privateHistoryIsNotGivenToAnotherPlayer() throws Exception {
        Fixture fixture = masterWithGame();
        UUID character = fixture.character("Иван", "Высокий", "Купец из Твери", "Боится воды");
        Viewer other = fixture.player();

        JsonNode seenByPlayer = readJson(call(
                get("/games/" + fixture.gameId() + "/characters/" + character), other.token(), null));

        assertThat(seenByPlayer.get("privateHistory").isNull())
                .as("закрытая история не приходит постороннему игроку")
                .isTrue();
        assertThat(seenByPlayer.get("publicHistory").asText()).isEqualTo("Купец из Твери");
        assertThat(seenByPlayer.get("appearance").asText())
                .as("внешность видна всем, кому виден персонаж")
                .isEqualTo("Высокий");

        JsonNode seenByMaster = readJson(call(
                get("/games/" + fixture.gameId() + "/characters/" + character), fixture.token(), null));
        assertThat(seenByMaster.get("privateHistory").asText()).isEqualTo("Боится воды");
    }

    /** Правка карточки — отдельное событие от архивации: игроку показывают разные вещи (`D-009`). */
    @Test
    void cardEditLeavesItsOwnEvent() throws Exception {
        Fixture fixture = masterWithGame();
        UUID character = fixture.character("Иван", "Высокий", "Купец", "Тайна");

        MvcResult updated = call(
                patch("/games/" + fixture.gameId() + "/characters/" + character),
                fixture.token(),
                card("Иван", "Седой", "Купец из Твери", "Тайна"));

        assertThat(updated.getResponse().getStatus()).isEqualTo(200);
        assertThat(readField(updated, "appearance")).isEqualTo("Седой");
        assertThat(eventTypes(fixture.gameId()))
                .containsExactly("GAME_CREATED", "CHARACTER_CREATED", "CHARACTER_CONTENT_CHANGED");
    }

    /** Портрет обязан быть ассетом этой же игры: чужая картинка не привязывается. */
    @Test
    void portraitMustBelongToTheSameGame() throws Exception {
        Fixture fixture = masterWithGame();
        UUID foreign = fixture.assetOfAnotherGame();

        MvcResult refused = call(post("/games/" + fixture.gameId() + "/characters"),
                fixture.token(), cardWithPortrait("Иван", foreign));
        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(readField(refused, "code")).isEqualTo("asset_other_game");

        MvcResult missing = call(post("/games/" + fixture.gameId() + "/characters"),
                fixture.token(), cardWithPortrait("Иван", UUID.randomUUID()));
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        assertThat(readField(missing, "code")).isEqualTo("asset_not_found");
    }

    @Test
    void characterFromAnotherGameIsNotFound() throws Exception {
        Fixture fixture = masterWithGame();
        UUID stranger = masterWithGame().character("Чужак", null, null, null);

        MvcResult missing = call(get("/games/" + fixture.gameId() + "/characters/" + stranger),
                fixture.token(), null);

        assertThat(missing.getResponse().getStatus())
                .as("чужой персонаж не выдаёт своего существования")
                .isEqualTo(404);
    }

    /** После `FINISHED` мир read-only (`D-094`); архивация остаётся — она закрывает, а не меняет. */
    @Test
    void finishedGameRefusesCardEditsAndMovementButAllowsArchiving() throws Exception {
        Fixture fixture = masterWithGame();
        UUID location = fixture.location("Финал");
        UUID character = fixture.character("Иван", "Высокий", null, null);
        fixture.place(character, location);
        fixture.start();
        call(post("/games/" + fixture.gameId() + "/transitions"), fixture.token(),
                Map.of("transition", "FINISH"));

        assertThat(call(patch("/games/" + fixture.gameId() + "/characters/" + character),
                        fixture.token(), card("Иван", "Седой", null, null))
                        .getResponse().getStatus())
                .isEqualTo(409);
        assertThat(call(put("/games/" + fixture.gameId() + "/characters/" + character + "/location"),
                        fixture.token(), Map.of("chatId", location.toString()))
                        .getResponse().getStatus())
                .isEqualTo(409);
        assertThat(call(post("/games/" + fixture.gameId() + "/characters/" + character + "/archive"),
                        fixture.token(), Map.of("reason", "игра закрыта"))
                        .getResponse().getStatus())
                .isEqualTo(200);
    }

    // --- Присутствие -------------------------------------------------------------------

    /** Мастер размещает персонажа; уход и вход — два события и две строки истории (`D-007`). */
    @Test
    void movementKeepsPresenceHistoryAndWritesBothEvents() throws Exception {
        Fixture fixture = masterWithGame();
        UUID hall = fixture.location("Зал");
        UUID corridor = fixture.location("Коридор");
        UUID character = fixture.character("Иван", "Высокий", null, null);

        fixture.place(character, hall);
        fixture.place(character, corridor);

        assertThat(countRows("SELECT count(*) FROM presence WHERE character_id = ?", character))
                .as("уход закрывает строку, а не переписывает её (D-008)")
                .isEqualTo(2);
        assertThat(countRows(
                        "SELECT count(*) FROM presence WHERE character_id = ? AND left_at IS NULL",
                        character))
                .isEqualTo(1);
        assertThat(eventTypes(fixture.gameId()))
                .containsExactly(
                        "GAME_CREATED",
                        "CHAT_CREATED",
                        "CHAT_CREATED",
                        "CHARACTER_CREATED",
                        "CHARACTER_ENTERED",
                        "CHARACTER_LEFT",
                        "CHARACTER_ENTERED");

        fixture.place(character, corridor);

        assertThat(eventTypes(fixture.gameId()))
                .as("повторное размещение в ту же локацию ничего не меняет")
                .hasSize(7);
        assertThat(countRows("SELECT count(*) FROM presence WHERE character_id = ?", character))
                .isEqualTo(2);
    }

    @Test
    void playerSeesWhoIsPresent() throws Exception {
        Fixture fixture = masterWithGame();
        UUID hall = fixture.location("Зал");
        UUID character = fixture.character("Иван", "Высокий", null, null);
        fixture.place(character, hall);

        assertThat(presentNames(playerGraph(fixture, fixture.player()), hall))
                .containsExactly("Иван");
    }

    // --- Скрытность --------------------------------------------------------------------

    /**
     * Критерий приёмки `E06`: скрытый персонаж отсутствует в ответе графа и в списках
     * присутствующих для другого игрока, а «ушёл» читается по событию (`D-007`).
     */
    @Test
    void hiddenCharacterDisappearsFromGraphForOthersAndLeavesAnAppearedLeft() throws Exception {
        Fixture fixture = masterWithGame();
        UUID hall = fixture.location("Зал");
        UUID character = fixture.character("Иван", "Высокий", null, null);
        fixture.place(character, hall);
        Viewer player = fixture.player();

        assertThat(presentNames(playerGraph(fixture, player), hall))
                .as("до скрытия персонаж виден")
                .containsExactly("Иван");

        fixture.stealth(character, true, "ушёл в тень");

        assertThat(presentNames(playerGraph(fixture, player), hall))
                .as("скрытый персонаж не приходит игроку вовсе, а не приходит помеченным")
                .isEmpty();

        JsonNode masterView = readJson(call(
                get("/games/" + fixture.gameId() + "/graph"), fixture.token(), null));
        assertThat(presentNames(masterView, hall)).as("мастер видит всех").containsExactly("Иван");
        assertThat(masterView.at("/nodes/0/present/0/hidden").asBoolean())
                .as("мастеру видно, что персонаж скрыт")
                .isTrue();

        assertThat(eventTypes(fixture.gameId()))
                .as("для остальных он «отобразился как ушедший» (D-007)")
                .contains("CHARACTER_STEALTHED", "CHARACTER_APPEARED_LEFT");
    }

    /** Идемпотентность: повторное скрытие не пишет второе событие, выход возвращает всех на место. */
    @Test
    void stealthIsIdempotentAndRevealRestoresVisibility() throws Exception {
        Fixture fixture = masterWithGame();
        UUID hall = fixture.location("Зал");
        UUID character = fixture.character("Иван", "Высокий", null, null);
        fixture.place(character, hall);
        Viewer player = fixture.player();

        fixture.stealth(character, true, "первый раз");
        fixture.stealth(character, true, "второй раз");

        assertThat(countRows(
                        "SELECT count(*) FROM game_event WHERE game_id = ? AND type = 'CHARACTER_STEALTHED'",
                        fixture.gameId()))
                .isEqualTo(1);
        assertThat(countRows(
                        "SELECT count(*) FROM game_event WHERE game_id = ? AND type = 'CHARACTER_APPEARED_LEFT'",
                        fixture.gameId()))
                .isEqualTo(1);

        fixture.stealth(character, false, null);

        assertThat(countRows(
                        "SELECT count(*) FROM game_event WHERE game_id = ? AND type = 'CHARACTER_REVEALED'",
                        fixture.gameId()))
                .isEqualTo(1);
        assertThat(presentNames(playerGraph(fixture, player), hall)).containsExactly("Иван");

        fixture.stealth(character, true, "и снова в тень");

        assertThat(countRows(
                        "SELECT count(*) FROM game_event WHERE game_id = ? AND type = 'CHARACTER_STEALTHED'",
                        fixture.gameId()))
                .as("повторный уход в скрытность — новая мутация, и для остальных снова «ушёл»")
                .isEqualTo(2);
    }

    /** Скрытность переезжает вместе с персонажем: иначе перемещение молча показало бы его всем. */
    @Test
    void hiddenCharacterStaysHiddenAfterMovement() throws Exception {
        Fixture fixture = masterWithGame();
        UUID hall = fixture.location("Зал");
        UUID corridor = fixture.location("Коридор");
        UUID character = fixture.character("Иван", "Высокий", null, null);
        fixture.place(character, hall);
        fixture.stealth(character, true, "ушёл в тень");
        Viewer player = fixture.player();

        fixture.place(character, corridor);

        assertThat(presentNames(playerGraph(fixture, player), corridor))
                .as("перемещение скрытого персонажа не раскрывает его")
                .isEmpty();
    }

    @Test
    void stealthWithoutPlacementIsRefused() throws Exception {
        Fixture fixture = masterWithGame();
        UUID character = fixture.character("Иван", "Высокий", null, null);

        MvcResult refused = call(
                put("/games/" + fixture.gameId() + "/characters/" + character + "/stealth"),
                fixture.token(),
                Map.of("hidden", true));

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(readField(refused, "code")).isEqualTo("character_not_placed");
    }

    // --- Архивация и отказы ------------------------------------------------------------

    /** Критерий приёмки: архивация выводит персонажа из мира, строка остаётся и читается (`D-009`). */
    @Test
    void archivedCharacterLeavesTheWorldButKeepsItsRowAndReason() throws Exception {
        Fixture fixture = masterWithGame();
        UUID hall = fixture.location("Зал");
        UUID character = fixture.character("Иван", "Высокий", null, null);
        fixture.place(character, hall);

        MvcResult archived = call(
                post("/games/" + fixture.gameId() + "/characters/" + character + "/archive"),
                fixture.token(),
                Map.of("reason", "уехал"));

        assertThat(archived.getResponse().getStatus()).isEqualTo(200);
        assertThat(readField(archived, "archived")).isEqualTo("true");
        assertThat(readField(archived, "archivedReason")).isEqualTo("уехал");
        assertThat(countRows(
                        "SELECT count(*) FROM presence WHERE character_id = ? AND left_at IS NULL",
                        character))
                .as("архивированный персонаж не остаётся в списках присутствующих живой игры")
                .isZero();
        assertThat(presentNames(readJson(call(
                        get("/games/" + fixture.gameId() + "/graph"), fixture.token(), null)), hall))
                .isEmpty();
        assertThat(countRows("SELECT count(*) FROM character WHERE id = ?", character))
                .as("архивация не удаляет строку")
                .isEqualTo(1);

        call(post("/games/" + fixture.gameId() + "/characters/" + character + "/archive"),
                fixture.token(),
                Map.of("reason", "второй раз"));
        assertThat(countRows(
                        "SELECT count(*) FROM game_event WHERE game_id = ? AND type = 'CHARACTER_ARCHIVED'",
                        fixture.gameId()))
                .as("повторная архивация идемпотентна")
                .isEqualTo(1);
    }

    /** Удаление персонажа не предусмотрено ни до, ни после `READY` (`D-009`, `D-010`). */
    @Test
    void deletingACharacterIsRefusedBeforeAndAfterReady() throws Exception {
        Fixture fixture = masterWithGame();
        UUID character = fixture.character("Иван", "Высокий", null, null);
        // Размещение нужно, чтобы игра дошла до `READY`: с `E07` это блокирующая проверка
        // готовности (`D-112`), а тест проверяет отказ в удалении именно после старта.
        fixture.place(character, fixture.location("Зал"));

        MvcResult before = call(
                delete("/games/" + fixture.gameId() + "/characters/" + character), fixture.token(), null);
        assertThat(before.getResponse().getStatus()).isEqualTo(409);
        assertThat(readField(before, "code")).isEqualTo("character_not_deletable");
        assertThat(readField(before, "detail")).contains("архивац");

        fixture.start();

        MvcResult after = call(
                delete("/games/" + fixture.gameId() + "/characters/" + character), fixture.token(), null);
        assertThat(after.getResponse().getStatus()).isEqualTo(409);
        assertThat(countRows("SELECT count(*) FROM character WHERE id = ?", character)).isEqualTo(1);
    }

    /** До `READY` архивный персонаж игроку не показывается, после — «выведен из игры» (`D-009`). */
    @Test
    void archivedCharacterIsInvisibleToPlayerUntilReady() throws Exception {
        Fixture fixture = masterWithGame();
        UUID character = fixture.character("Иван", "Высокий", null, null);
        Viewer player = fixture.player();
        fixture.archive(character, "передумал");

        assertThat(characterNames(readJson(call(
                        get("/games/" + fixture.gameId() + "/characters"), player.token(), null))))
                .as("до старта игрок не узнаёт, что мастер кого-то заводил и передумал (D-009)")
                .containsExactly("Наблюдатель");

        fixture.start();

        JsonNode afterReady = readJson(call(
                get("/games/" + fixture.gameId() + "/characters"), player.token(), null));
        assertThat(characterNames(afterReady))
                .as("после старта архивация показывается как «выведен из игры» с причиной")
                .containsExactlyInAnyOrder("Иван", "Наблюдатель");
        JsonNode archived = null;
        for (JsonNode card : afterReady) {
            if ("Иван".equals(card.get("name").asText())) {
                archived = card;
            }
        }
        assertThat(archived).as("архивный персонаж присутствует в ответе").isNotNull();
        assertThat(archived.get("archivedReason").asText()).isEqualTo("передумал")
                .as("игроку показывается причина, а не пустое место");
    }

    @Test
    void placementIntoArchivedLocationIsRefused() throws Exception {
        Fixture fixture = masterWithGame();
        UUID hall = fixture.location("Зал");
        UUID character = fixture.character("Иван", "Высокий", null, null);
        call(post("/games/" + fixture.gameId() + "/locations/" + hall + "/archive"),
                fixture.token(), Map.of("reason", "завалило"));

        MvcResult refused = call(
                put("/games/" + fixture.gameId() + "/characters/" + character + "/location"),
                fixture.token(),
                Map.of("chatId", hall.toString()));

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(readField(refused, "code")).isEqualTo("chat_archived");
    }

    /**
     * Ни одна запись о персонаже не проходит мимо мастера (`D-065`). Перемещение и скрытность
     * тоже его: право игрока на них неотделимо от владения слотом, а слот — `E13`, поэтому
     * здесь отказ, а не частичное разрешение (`D-107`, `D-108`).
     */
    @Test
    void playerCannotWriteAnythingAboutCharacters() throws Exception {
        Fixture fixture = masterWithGame();
        UUID hall = fixture.location("Зал");
        UUID character = fixture.character("Иван", "Высокий", null, null);
        fixture.place(character, hall);
        String player = fixture.player().token();
        List<String> before = eventTypes(fixture.gameId());

        assertThat(call(post("/games/" + fixture.gameId() + "/characters"), player,
                        card("Самозванец", null, null, null))
                        .getResponse().getStatus())
                .isEqualTo(403);
        assertThat(call(put("/games/" + fixture.gameId() + "/characters/" + character + "/stealth"),
                        player, Map.of("hidden", true))
                        .getResponse().getStatus())
                .isEqualTo(403);
        assertThat(call(put("/games/" + fixture.gameId() + "/characters/" + character + "/location"),
                        player, Map.of("chatId", hall.toString()))
                        .getResponse().getStatus())
                .isEqualTo(403);
        assertThat(call(patch("/games/" + fixture.gameId() + "/characters/" + character),
                        player, card("Иван", "Седой", null, null))
                        .getResponse().getStatus())
                .isEqualTo(403);

        assertThat(eventTypes(fixture.gameId()))
                .as("отказы не оставляют событий о мире")
                .isEqualTo(before);
    }

    // --- обвязка -----------------------------------------------------------------------

    private static Map<String, Object> card(
            String name, String appearance, String publicHistory, String privateHistory) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("appearance", appearance);
        payload.put("publicHistory", publicHistory);
        payload.put("privateHistory", privateHistory);
        payload.put("playable", true);
        payload.put("npc", false);
        return payload;
    }

    private static Map<String, Object> cardWithPortrait(String name, UUID portraitAssetId) {
        Map<String, Object> payload = card(name, null, null, null);
        payload.put("portraitAssetId", portraitAssetId.toString());
        return payload;
    }

    /**
     * Граф глазами игрока: `E04` требует `asCharacter` — «чьими глазами» (`D-103`), и роль
     * смотрящего проверяется на входе. Мастер смотрит без него.
     */
    private JsonNode playerGraph(Fixture fixture, Viewer viewer) throws Exception {
        return readJson(call(
                get("/games/" + fixture.gameId() + "/graph?asCharacter=" + viewer.characterId()),
                viewer.token(),
                null));
    }

    private static List<String> presentNames(JsonNode graph, UUID chatId) {
        for (JsonNode node : graph.get("nodes")) {
            if (node.get("id").asText().equals(chatId.toString())) {
                List<String> names = new ArrayList<>();
                node.get("present").forEach(person -> names.add(person.get("name").asText()));
                return names;
            }
        }
        throw new AssertionError("локация не найдена в графе: " + chatId);
    }

    private static List<String> characterNames(JsonNode list) {
        List<String> names = new ArrayList<>();
        list.forEach(character -> names.add(character.get("name").asText()));
        return names;
    }

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

    /** Смотрящий игрок: его сессия и персонаж, от чьего лица он видит граф. */
    private record Viewer(String token, UUID characterId) {
    }

    /** Игра мастера со средствами строить мир: локации, персонажи и второй участник. */
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

        private UUID character(
                String name, String appearance, String publicHistory, String privateHistory)
                throws Exception {
            MvcResult created = call(post("/games/" + gameId + "/characters"), token,
                    card(name, appearance, publicHistory, privateHistory));
            assertThat(created.getResponse().getStatus()).isEqualTo(201);
            return UUID.fromString(readField(created, "id"));
        }

        private void place(UUID characterId, UUID chatId) throws Exception {
            MvcResult placed = call(
                    put("/games/" + gameId + "/characters/" + characterId + "/location"),
                    token,
                    Map.of("chatId", chatId.toString()));
            assertThat(placed.getResponse().getStatus()).isEqualTo(200);
        }

        private void stealth(UUID characterId, boolean hidden, String reason) throws Exception {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("hidden", hidden);
            if (reason != null) {
                payload.put("reason", reason);
            }
            MvcResult result = call(
                    put("/games/" + gameId + "/characters/" + characterId + "/stealth"), token, payload);
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
        }

        private void archive(UUID characterId, String reason) throws Exception {
            MvcResult result = call(
                    post("/games/" + gameId + "/characters/" + characterId + "/archive"),
                    token,
                    Map.of("reason", reason));
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
        }

        /**
         * Второй участник игры: роль `PLAYER` со своим персонажем-наблюдателем — «чужой» для
         * карточек первого. Занятие выставлено прямо в базе: эндпоинт занятия им владеет `E13`,
         * а граф уже сейчас пускает игрока только своим персонажем (`D-102`).
         *
         * <p>Наблюдатель ставится в отдельную локацию, а не в ту, что проверяет тест: с `E07`
         * валидация готовности (`D-112`) требует размещения играбельного персонажа, поэтому
         * «нигде» его оставить нельзя. Отдельная локация сохраняет предмет прежних проверок —
         * списки присутствующих в проверяемой локации не должны измениться.
         */
        private Viewer player() throws Exception {
            String login = unique("Игрок");
            register(login, OTHER_PASSWORD, null);
            UUID playerId = jdbc.queryForObject(
                    "SELECT id FROM app_user WHERE login_normalized = ?",
                    UUID.class,
                    loginNormalized(login));
            UUID membershipId = mutations.addMembership(
                    userId, gameId, playerId, MembershipRole.PLAYER, MembershipStatus.JOINED).id();
            UUID observer = UUID.fromString(readField(call(post("/games/" + gameId + "/characters"), token,
                    card("Наблюдатель", null, null, null)), "id"));
            place(observer, location("Окрестности " + unique("О")));
            jdbc.update("UPDATE character SET occupant_membership_id = ? WHERE id = ?",
                    membershipId, observer);
            return new Viewer(loginAndGetToken(login, OTHER_PASSWORD), observer);
        }

        /**
         * Ассет чужой игры: портрет из неё не должен привязываться к персонажу этой.
         * Ключ хранилища — сам идентификатор без каталогов: так его строит `AssetStore`,
         * и плоский ключ обязателен, иначе сборку мусора сорвёт чужой тестовый ряд (`D-075`).
         */
        private UUID assetOfAnotherGame() throws Exception {
            Fixture other = masterWithGame();
            UUID assetId = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO asset (id, game_id, owner_user_id, mime, byte_size, sha256, "
                            + "storage_key, original_name) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    assetId,
                    other.gameId(),
                    other.userId,
                    "image/png",
                    68L,
                    "0".repeat(64),
                    assetId.toString(),
                    "portrait.png");
            return assetId;
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
