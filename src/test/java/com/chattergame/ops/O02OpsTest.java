package com.chattergame.ops;

import com.chattergame.game.Game;
import com.chattergame.game.GameMutationService;
import com.chattergame.user.E02TestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Служебный контур эксплуатации (`PLAN` O02, `D-147`).
 *
 * <p>Проверяется главное свойство: числа узла не отдаются без токена и не отдаются
 * по сессии игрока. Токен здесь задан свойством теста, потому что в проде он приходит
 * из окружения, а пустой токен должен выключать маршруты целиком.
 */
@TestPropertySource(properties = "chattergame.ops.token=ops-test-token")
class O02OpsTest extends E02TestSupport {

    private static final String TOKEN = "ops-test-token";

    @Autowired
    private GameMutationService mutations;

    @Autowired
    private OpsService ops;

    @Test
    void metricsRequireTheOpsTokenAndNotAPlayerSession() throws Exception {
        String playerToken = registerAndGetToken(unique("Оператор"), PASSWORD);

        MvcResult anonymous = call(get("/ops/metrics"), null, null);
        assertThat(anonymous.getResponse().getStatus())
                .as("без токена числа узла не отдаются")
                .isEqualTo(401);

        MvcResult player = mvc.perform(get("/ops/metrics")
                        .cookie(new jakarta.servlet.http.Cookie(
                                com.chattergame.user.SessionCookie.NAME, playerToken)))
                .andReturn();
        assertThat(player.getResponse().getStatus())
                .as("сессия игрока служебный контур не открывает")
                .isEqualTo(401);

        MvcResult wrong = mvc.perform(get("/ops/metrics").header("X-Ops-Token", "не тот")).andReturn();
        assertThat(wrong.getResponse().getStatus()).isEqualTo(401);

        MvcResult ok = mvc.perform(get("/ops/metrics").header("X-Ops-Token", TOKEN)).andReturn();
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        var body = json.readTree(ok.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.get("sockets").get("total").asInt())
                .as("сокетов в тесте нет, но поле присутствует")
                .isZero();
        assertThat(body.get("llm").has("queued"))
                .as("глубина очереди LLM видна оператору")
                .isTrue();
        assertThat(body.get("journal").get("events").asLong())
                .as("журнал считается по всей базе")
                .isGreaterThanOrEqualTo(0);
    }

    @Test
    void journalSizeIsReportedPerGame() throws Exception {
        Fixture fixture = game();

        @SuppressWarnings("unchecked")
        Map<String, Object> journal = (Map<String, Object>) ops.metrics().get("journal");
        @SuppressWarnings("unchecked")
        Map<String, Long> byGame = (Map<String, Long>) journal.get("byGame");

        assertThat(byGame)
                .as("размер журнала виден по игре, а не только суммой")
                .containsKey(fixture.gameId.toString());
        assertThat(byGame.get(fixture.gameId.toString())).isPositive();
    }

    @Test
    void orphanDryRunNeverDeletesAnything() throws Exception {
        MvcResult result = mvc.perform(post("/ops/orphans/dry-run").header("X-Ops-Token", TOKEN))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        var body = json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.get("dryRun").asBoolean())
                .as("прогон только перечисляет кандидатов (D-077)")
                .isTrue();
        assertThat(body.get("deleted")).isEmpty();
    }

    @Test
    void archivingWithoutAReasonIsRefused() throws Exception {
        Fixture fixture = game();
        String masterToken = loginAndGetToken(fixture.masterLogin, PASSWORD);

        MvcResult result = call(
                post("/games/" + fixture.gameId + "/archive"),
                masterToken,
                Map.of("reason", " "));
        assertThat(result.getResponse().getStatus())
                .as("архив без причины не объясняет участникам, почему игра встала (D-010)")
                .isEqualTo(400);
        assertThat(countRows("SELECT count(*) FROM game WHERE id = ? AND archived", fixture.gameId))
                .isZero();
    }

    @Test
    void archivingIsRefusedToEveryoneButTheMaster() throws Exception {
        Fixture fixture = game();
        String strangerToken = registerAndGetToken(unique("Посторонний"), PASSWORD);

        MvcResult result = call(
                post("/games/" + fixture.gameId + "/archive"),
                strangerToken,
                Map.of("reason", "хочу закрыть чужую игру"));
        assertThat(result.getResponse().getStatus())
                .as("архив чужой игры — действие мастера (D-010)")
                .isIn(403, 404);
        assertThat(countRows("SELECT count(*) FROM game WHERE id = ? AND archived", fixture.gameId))
                .as("чужой запрос игру не архивирует")
                .isZero();
    }

    private record Fixture(UUID gameId, String masterLogin) {
    }

    private Fixture game() throws Exception {
        String login = unique("МастерОпераций");
        String token = registerAndGetToken(login, PASSWORD);
        UUID userId = jdbc.queryForObject(
                "SELECT id FROM app_user WHERE login_normalized = ?",
                UUID.class,
                loginNormalized(login));
        Game game = mutations.createGame(userId, "Операции " + unique("O02"), null, null);
        assertThat(token).isNotNull();
        return new Fixture(game.id(), login);
    }
}
