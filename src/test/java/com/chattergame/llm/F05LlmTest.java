package com.chattergame.llm;

import com.chattergame.request.E14TestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Опции модели и превращение её ответа в заявку (`PLAN F05`, критерии приёмки; `D-042`,
 * `D-043`, `D-044`).
 *
 * <p>Провайдер подменён: настоящий вызов стоил бы денег и был бы нестабилен, а проверяется
 * не сеть, а контракт — «ответ стал заявкой», «мир до одобрения не менялся», «классы отказа
 * различимы». Модель при этом не мокается: заявки, операции и применение идут настоящим
 * путём через БД.
 */
class F05LlmTest extends E14TestSupport {

    private static final String ENV_KEY_BASE64 = Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    @DynamicPropertySource
    static void llmKey(DynamicPropertyRegistry registry) {
        registry.add("chattergame.llm.secret-key", () -> ENV_KEY_BASE64);
    }

    private static final String ANSWER = """
            {"operations":[
              {"op":"update_character_field","payload":{"characterId":"%s","field":"appearance","value":"седой"}},
              {"op":"create_item","payload":{"storageId":"%s","name":"Заявка-меч","size":"SMALL"}},
              {"op":"move_character","payload":{"characterId":"%s","transitionId":"%s"}}
            ]}
            """;

    @TestConfiguration
    static class StubConfig {

        @Bean
        @Primary
        StubProvider stubProvider() {
            return new StubProvider();
        }
    }

    static final class StubProvider implements LlmProvider {

        static final AtomicReference<String> answer = new AtomicReference<>("");
        static final AtomicReference<LlmCallException> failure = new AtomicReference<>();
        static final AtomicInteger calls = new AtomicInteger();

        @Override
        public String complete(Request request) {
            calls.incrementAndGet();
            LlmCallException error = failure.get();
            if (error != null) {
                throw error;
            }
            return answer.get();
        }
    }

    @BeforeEach
    void resetProvider() {
        StubProvider.answer.set("");
        StubProvider.failure.set(null);
        StubProvider.calls.set(0);
    }

    private void activateCredential(String token) throws Exception {
        MvcResult created = call(
                post("/me/llm-credentials"),
                token,
                Map.of("baseUrl", "http://stub.local/v1", "model", "stub-model", "secret", "sk-stub"));
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        String id = json.readTree(bodyOf(created)).path("id").asText();
        MvcResult activated =
                call(post("/me/llm-credentials/" + id + "/activate"), token, null);
        assertThat(activated.getResponse().getStatus()).isEqualTo(200);
    }

    private MvcResult propose(
            Fixture fixture, String type, String instruction, String idempotencyKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("instruction", instruction);
        if (idempotencyKey != null) {
            body.put("idempotencyKey", idempotencyKey);
        }
        return call(
                post("/games/" + fixture.game() + "/llm/proposals"), fixture.master().token(), body);
    }

    private String answerFor(Fixture fixture) {
        return ANSWER.formatted(
                fixture.character(), fixture.storage(), fixture.character(), fixture.transition());
    }

    @Test
    void withoutActiveKeyOptionsAreUnavailableAndProposalIsRefused() throws Exception {
        Fixture fixture = fixture();

        MvcResult status = call(
                get("/games/" + fixture.game() + "/llm"), fixture.master().token(), null);
        assertThat(status.getResponse().getStatus()).isEqualTo(200);
        JsonNode view = json.readTree(bodyOf(status));
        assertThat(view.path("available").asBoolean())
                .as("без ключа опции модели недоступны целиком (D-042)")
                .isFalse();
        assertThat(view.path("reason").asText()).isEqualTo("llm_not_configured");

        MvcResult refused = propose(fixture, "CO_MASTER", "Опиши сцену", null);
        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(bodyOf(refused)).path("code").asText())
                .isEqualTo("llm_not_configured");
        assertThat(countRows("SELECT count(*) FROM request WHERE game_id = ?", fixture.game()))
                .as("отказ до записи: заявка не создаётся")
                .isZero();
        assertThat(StubProvider.calls.get()).isZero();
    }

    @Test
    void proposalBecomesRequestAndDoesNotChangeTheWorldBeforeApproval() throws Exception {
        Fixture fixture = fixture();
        activateCredential(fixture.master().token());
        StubProvider.answer.set(answerFor(fixture));

        MvcResult proposed = propose(fixture, "CO_MASTER", "Предложи правки", null);

        assertThat(proposed.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json.readTree(bodyOf(proposed));
        assertThat(body.path("origin").asText()).isEqualTo("LLM");
        assertThat(body.path("status").asText())
                .as("ответ модели — предложение, а не применённая правка")
                .isEqualTo("SUCCEEDED");
        assertThat(body.path("operations")).hasSize(3);
        assertThat(body.path("operations").get(0).path("status").asText()).isEqualTo("PENDING");
        assertThat(fixture.fieldOf("appearance"))
                .as("до решения мастера мир не меняется (D-040)")
                .isEqualTo("высокий");
        assertThat(fixture.itemCount()).isZero();
    }

    @Test
    void partialApprovalAppliesOnlyTheChosenOperations() throws Exception {
        Fixture fixture = fixture();
        activateCredential(fixture.master().token());
        StubProvider.answer.set(answerFor(fixture));
        String requestId = json.readTree(bodyOf(propose(fixture, "CO_MASTER", "Предложи", null)))
                .path("id")
                .asText();

        MvcResult decided = call(
                post("/games/" + fixture.game() + "/requests/" + requestId + "/decision"),
                fixture.master().token(),
                Map.of("approve", List.of(1, 3)));

        JsonNode body = json.readTree(bodyOf(decided));
        assertThat(body.path("status").asText()).isEqualTo("PARTIALLY_APPROVED");
        assertThat(fixture.fieldOf("appearance")).isEqualTo("седой");
        assertThat(fixture.itemCount())
                .as("отклонённая операция не оставила побочного эффекта")
                .isZero();
        assertThat(operationStatus(UUID.fromString(requestId), 2)).isEqualTo("REJECTED");
    }

    @Test
    void providerFailuresKeepDistinctCodes() throws Exception {
        Fixture fixture = fixture();
        activateCredential(fixture.master().token());

        StubProvider.failure.set(new LlmCallException(LlmErrorKind.RATE_LIMIT, "429"));
        String rateLimited = json.readTree(bodyOf(propose(fixture, "CO_MASTER", "Раз", null)))
                .path("id")
                .asText();

        StubProvider.failure.set(new LlmCallException(LlmErrorKind.TIMEOUT, "60s"));
        String timedOut = json.readTree(bodyOf(propose(fixture, "CO_MASTER", "Два", null)))
                .path("id")
                .asText();

        StubProvider.failure.set(null);
        StubProvider.answer.set("извините, не могу");
        String invalid = json.readTree(bodyOf(propose(fixture, "CO_MASTER", "Три", null)))
                .path("id")
                .asText();

        assertThat(statusOf(UUID.fromString(rateLimited))).isEqualTo("FAILED");
        assertThat(errorOf(rateLimited)).startsWith("llm_rate_limited");
        assertThat(errorOf(timedOut)).startsWith("llm_timeout");
        assertThat(errorOf(invalid)).startsWith("llm_invalid_response");
    }

    @Test
    void repeatedIdempotencyKeyDoesNotCallTheProviderTwice() throws Exception {
        Fixture fixture = fixture();
        activateCredential(fixture.master().token());
        StubProvider.answer.set(answerFor(fixture));

        String first = json.readTree(bodyOf(propose(fixture, "CO_MASTER", "Предложи", "key-1")))
                .path("id")
                .asText();
        String second = json.readTree(bodyOf(propose(fixture, "CO_MASTER", "Предложи", "key-1")))
                .path("id")
                .asText();

        assertThat(second).isEqualTo(first);
        assertThat(StubProvider.calls.get()).isEqualTo(1);
    }

    private String errorOf(String requestId) {
        return jdbc.queryForObject(
                "SELECT error FROM request WHERE id = ?", String.class, UUID.fromString(requestId));
    }
}
