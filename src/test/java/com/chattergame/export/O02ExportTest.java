package com.chattergame.export;

import com.chattergame.character.Character;
import com.chattergame.chat.Chat;
import com.chattergame.game.Game;
import com.chattergame.game.GameMutationService;
import com.chattergame.game.GameTransition;
import com.chattergame.game.Membership;
import com.chattergame.game.MembershipRole;
import com.chattergame.game.MembershipStatus;
import com.chattergame.message.MessageKind;
import com.chattergame.message.MessageService;
import com.chattergame.message.SubjectKind;
import com.chattergame.note.NoteService;
import com.chattergame.user.E02TestSupport;
import com.chattergame.user.SessionCookie;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Экспорт, восстановление и архивация (`PLAN` O02, `D-061`, `D-010`, `D-146`).
 *
 * <p>Проверяются критерии приёмки этапа: комплект завершённой игры читается человеком и
 * машиной, содержит все чаты включая личные и закрытые истории с явной маркировкой,
 * восстанавливается обратно в базу; выгрузка недоступна никому, кроме мастера;
 * архивированная игра остаётся читаемой участниками.
 *
 * <p>Восстановление проверяется на живой базе рядом с оригиналом (`asNewGame`): так
 * проверяется и перенос строк, и то, что копия не делит идентификаторы с источником.
 */
class O02ExportTest extends E02TestSupport {

    @Autowired
    private GameMutationService mutations;

    @Autowired
    private MessageService writes;

    @Autowired
    private NoteService notes;

    @Test
    void theBundleContainsEveryChatAndMarksWhatIsClosed() throws Exception {
        Fixture fixture = finishedGame();

        String markdown = exportMarkdown(fixture);

        assertThat(markdown)
                .as("человеческая версия начинается с названия игры и предупреждения о закрытых частях")
                .contains("# " + fixture.title)
                .contains("ЗАКРЫТО")
                .as("открытая локация и её реплика на месте")
                .contains("Площадь")
                .contains("открытая реплика")
                .as("личный канал попадает в комплект и помечен (D-061)")
                .contains("шёпотом")
                .contains("WHISPER")
                .as("закрытая история персонажа выгружается с маркировкой")
                .contains("закрытая история первого")
                .as("заметки входят в комплект")
                .contains("заметка мастера");
    }

    @Test
    void theMachineVersionCarriesTablesAndTheClosedSection() throws Exception {
        Fixture fixture = finishedGame();

        String body = exportJson(fixture);
        var document = json.readTree(body);

        assertThat(document.get("format").asText()).isEqualTo(GameExportService.FORMAT);
        assertThat(document.get("version").asInt()).isEqualTo(GameExportService.VERSION);
        assertThat(document.get("gameId").asText()).isEqualTo(fixture.gameId.toString());
        assertThat(document.get("closed").get("notice").asText())
                .as("машина тоже видит, что файл не для игроков")
                .contains("только мастеру");
        assertThat(document.get("closed").get("noteIds")).isNotEmpty();
        assertThat(document.get("closed").get("characterHistories")).isNotEmpty();
        assertThat(document.get("tables").get("chat").isArray()).isTrue();
        assertThat(document.get("tables").get("message").size())
                .as("переписка выгружается целиком")
                .isGreaterThan(0);
        assertThat(document.get("tables").get("game_event").size())
                .as("журнал входит в снимок")
                .isGreaterThan(0);
    }

    @Test
    void onlyTheMasterGetsTheBundle() throws Exception {
        Fixture fixture = finishedGame();

        MvcResult player = call(
                get("/games/" + fixture.gameId + "/export.json"), fixture.firstToken, null);
        assertThat(player.getResponse().getStatus())
                .as("участник выгрузку не получает (D-021)")
                .isEqualTo(403);
        assertThat(player.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("export_master_only");

        String outsiderToken = registerAndGetToken(unique("Посторонний"), PASSWORD);
        MvcResult outsider = call(
                get("/games/" + fixture.gameId + "/export.md"), outsiderToken, null);
        assertThat(outsider.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void theSnapshotRestoresBackIntoTheDatabase() throws Exception {
        Fixture fixture = finishedGame();
        String body = exportJson(fixture);

        MvcResult result = mvc.perform(post("/games/import")
                        .param("asNewGame", "true")
                        .cookie(new Cookie(SessionCookie.NAME, fixture.masterToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("снимок принимается и восстанавливается рядом с оригиналом")
                .isEqualTo(201);
        UUID restored = UUID.fromString(
                json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .get("gameId").asText());
        assertThat(restored)
                .as("копия не делит идентификатор с оригиналом")
                .isNotEqualTo(fixture.gameId);

        assertThat(countRows("SELECT count(*) FROM game WHERE id = ?", restored)).isEqualTo(1);
        assertThat(countRows("SELECT count(*) FROM chat WHERE game_id = ?", restored))
                .as("все чаты, включая личный канал, восстановлены")
                .isEqualTo(countRows("SELECT count(*) FROM chat WHERE game_id = ?", fixture.gameId));
        assertThat(countRows("SELECT count(*) FROM message WHERE game_id = ?", restored))
                .as("переписка восстановлена целиком")
                .isEqualTo(countRows("SELECT count(*) FROM message WHERE game_id = ?", fixture.gameId));
        assertThat(countRows("SELECT count(*) FROM game_event WHERE game_id = ?", restored))
                .as("журнал восстановлен как данные, а не проигран заново (D-146)")
                .isEqualTo(countRows("SELECT count(*) FROM game_event WHERE game_id = ?", fixture.gameId));
        assertThat(countRows("SELECT count(*) FROM note WHERE game_id = ?", restored))
                .isEqualTo(countRows("SELECT count(*) FROM note WHERE game_id = ?", fixture.gameId));
        assertThat(jdbc.queryForObject("SELECT title FROM game WHERE id = ?", String.class, restored))
                .isEqualTo(fixture.title);
        assertThat(countRows(
                        "SELECT count(*) FROM message m WHERE m.game_id = ? "
                                + "AND NOT EXISTS (SELECT 1 FROM chat c "
                                + "WHERE c.id = m.chat_id AND c.game_id = ?)",
                        restored, restored))
                .as("ссылки внутри копии указывают на её собственные строки, а не на оригинал")
                .isZero();
    }

    @Test
    void aSnapshotOfAnExistingGameNeedsTheExplicitFlag() throws Exception {
        Fixture fixture = finishedGame();
        String body = exportJson(fixture);

        MvcResult result = mvc.perform(post("/games/import")
                        .cookie(new Cookie(SessionCookie.NAME, fixture.masterToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("молча перезаписать игру нельзя")
                .isEqualTo(409);
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("import_game_exists");
    }

    @Test
    void anArchivedGameStaysReadableByItsPlayers() throws Exception {
        Fixture fixture = finishedGame();

        MvcResult archived = call(
                post("/games/" + fixture.gameId + "/archive"),
                fixture.masterToken,
                Map.of("reason", "мастер ушёл в отпуск"));
        assertThat(archived.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(archived.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .get("archived").asBoolean())
                .isTrue();

        MvcResult read = call(get("/games/" + fixture.gameId), fixture.firstToken, null);
        assertThat(read.getResponse().getStatus())
                .as("архив не прячет игру от участников (D-010)")
                .isEqualTo(200);

        MvcResult feed = call(
                get("/games/" + fixture.gameId + "/chats/" + fixture.place + "/messages")
                        .param("asCharacter", fixture.firstCharacterId.toString()),
                fixture.firstToken,
                null);
        assertThat(feed.getResponse().getStatus())
                .as("история остаётся читаемой: "
                        + feed.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(200);
        assertThat(countRows("SELECT count(*) FROM game WHERE id = ?", fixture.gameId))
                .as("физического удаления нет")
                .isEqualTo(1);
    }

    private String exportJson(Fixture fixture) throws Exception {
        MvcResult result = call(
                get("/games/" + fixture.gameId + "/export.json"), fixture.masterToken, null);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getHeader("Content-Disposition"))
                .as("браузер получает файл, а не страницу")
                .contains("attachment");
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String exportMarkdown(Fixture fixture) throws Exception {
        MvcResult result = call(
                get("/games/" + fixture.gameId + "/export.md"), fixture.masterToken, null);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentType()).contains("text/markdown");
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static final class Fixture {

        final UUID gameId;
        final String masterToken;
        final UUID place;
        final String title;
        final String firstToken;
        final UUID firstCharacterId;

        private Fixture(
                UUID gameId,
                String masterToken,
                UUID place,
                String title,
                String firstToken,
                UUID firstCharacterId) {
            this.gameId = gameId;
            this.masterToken = masterToken;
            this.place = place;
            this.title = title;
            this.firstToken = firstToken;
            this.firstCharacterId = firstCharacterId;
        }
    }

    /**
     * Завершённая игра со всем, что обязан нести комплект: открытая локация, личный канал,
     * закрытая история, заметка и записи в журнале.
     */
    private Fixture finishedGame() throws Exception {
        String masterLogin = unique("МастерЭкспорта");
        String masterToken = registerAndGetToken(masterLogin, PASSWORD);
        UUID masterUserId = jdbc.queryForObject(
                "SELECT id FROM app_user WHERE login_normalized = ?",
                UUID.class,
                loginNormalized(masterLogin));

        String title = "Экспорт " + unique("O02");
        Game game = mutations.createGame(masterUserId, title, "открытый мир", "закрытый мир мастера");
        UUID place = mutations.createLocation(masterUserId, game.id(), "Площадь", "центр города").id();
        mutations.applyTransition(masterUserId, game.id(), GameTransition.MARK_READY, null);
        mutations.applyTransition(masterUserId, game.id(), GameTransition.START, null);

        Player first = player(masterUserId, game.id(), "Первый", "закрытая история первого");
        Player second = player(masterUserId, game.id(), "Второй", null);
        mutations.placeCharacter(masterUserId, game.id(), first.characterId, place);
        mutations.placeCharacter(masterUserId, game.id(), second.characterId, place);

        writes.post(masterUserId, game.id(), place, first.characterId,
                MessageKind.SPEECH, "открытая реплика", false);

        Chat whisper = mutations.createWhisper(
                first.userId, game.id(), List.of(first.characterId, second.characterId));
        mutations.acceptWhisper(second.userId, game.id(), whisper.id());
        writes.post(first.userId, game.id(), whisper.id(), first.characterId,
                MessageKind.SPEECH, "сказано шёпотом", false);

        notes.create(masterUserId, game.id(), new NoteService.Draft(
                SubjectKind.CHARACTER, first.characterId, "заметка мастера",
                List.of(new NoteService.Draft.Keyword("площадь", false))));

        mutations.applyTransition(masterUserId, game.id(), GameTransition.FINISH, null);

        return new Fixture(game.id(), masterToken, place, title, first.token, first.characterId);
    }

    private Player player(UUID masterUserId, UUID gameId, String prefix, String privateHistory)
            throws Exception {
        String login = unique(prefix);
        register(login, OTHER_PASSWORD, null);
        UUID userId = jdbc.queryForObject(
                "SELECT id FROM app_user WHERE login_normalized = ?",
                UUID.class,
                loginNormalized(login));
        Membership membership = mutations.addMembership(
                masterUserId, gameId, userId, MembershipRole.PLAYER, MembershipStatus.JOINED);
        Character character = mutations.createCharacter(
                masterUserId, gameId, prefix + "-" + unique(""),
                null, "внешность", "открытая история", privateHistory, true, false);
        jdbc.update("UPDATE character SET occupant_membership_id = ? WHERE id = ?",
                membership.id(), character.id());
        return new Player(userId, character.id(), loginAndGetToken(login, OTHER_PASSWORD));
    }

    private record Player(UUID userId, UUID characterId, String token) {
    }
}
