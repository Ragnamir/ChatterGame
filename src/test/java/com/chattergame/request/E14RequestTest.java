package com.chattergame.request;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Заявки и машина применения change set через HTTP-слой (`PLAN` E14, критерии приёмки;
 * `D-040`, `D-041`, `D-135`, `D-137`, `D-138`).
 *
 * <p>Проверяется то, что из одного места не видно: заявка игрока не меняет мир сама по себе,
 * частичное одобрение применяет ровно названные операции и оставляет соседние нетронутыми,
 * откат возвращает прежнее значение из журнала, а повтор с тем же ключом не создаёт вторую
 * заявку. Все проверки идут через маршруты, потому что критерий приёмки назван именно про
 * «прямое обращение к API».
 */
class E14RequestTest extends E14TestSupport {

    private MvcResult submitRequest(Fixture fixture, Account author, Map<String, Object> body)
            throws Exception {
        return call(post("/games/" + fixture.gameId + "/requests"), author.token, body);
    }

    private static Map<String, Object> body(
            String type, Map<String, Object> payload, String idempotencyKey) {
        Map<String, Object> request = new java.util.LinkedHashMap<>();
        request.put("type", type);
        request.put("payload", payload);
        if (idempotencyKey != null) {
            request.put("idempotencyKey", idempotencyKey);
        }
        return request;
    }

    private UUID idOf(MvcResult result) throws Exception {
        return UUID.fromString(json.readTree(bodyOf(result)).path("id").asText());
    }

    @Test
    void playerRequestDoesNotChangeTheWorldBeforeApproval() throws Exception {
        Fixture fixture = fixture();
        String before = fixture.fieldOf("appearance");

        MvcResult submitted = submitRequest(
                fixture,
                fixture.player,
                body("CHARACTER_EDIT", changeSet(fieldOperation(fixture.characterId, "седой")), null));

        assertThat(submitted.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(bodyOf(submitted)).path("status").asText()).isEqualTo("PENDING");
        assertThat(fixture.fieldOf("appearance"))
                .as("заявка игрока мир не меняет: изменение начинается с решения мастера (D-040)")
                .isEqualTo(before);
    }

    @Test
    void partialApprovalAppliesExactlyTheNamedOperations() throws Exception {
        Fixture fixture = fixture();
        UUID request = idOf(submitRequest(
                fixture,
                fixture.player,
                body(
                        "CHARACTER_EDIT",
                        changeSet(
                                fieldOperation(fixture.characterId, "седой"),
                                itemOperation(fixture.storageId, "Заявка-меч"),
                                historyOperation(fixture.characterId, "новая открытая история")),
                        null)));

        MvcResult decided = call(
                post("/games/" + fixture.gameId + "/requests/" + request + "/decision"),
                fixture.master.token,
                Map.of("approve", List.of(1, 3)));

        JsonNode body = json.readTree(bodyOf(decided));
        assertThat(body.path("status").asText())
                .as("две операции применены, одна отклонена — это PARTIALLY_APPROVED (D-135)")
                .isEqualTo("PARTIALLY_APPROVED");
        assertThat(fixture.fieldOf("appearance")).isEqualTo("седой");
        assertThat(fixture.fieldOf("public_history")).isEqualTo("новая открытая история");
        assertThat(fixture.itemCount())
                .as("отклонённая операция не оставила побочного эффекта")
                .isZero();

        JsonNode operations = body.path("operations");
        assertThat(operations).hasSize(3);
        assertThat(operations.get(0).path("status").asText()).isEqualTo("APPLIED");
        assertThat(operations.get(1).path("status").asText()).isEqualTo("REJECTED");
        assertThat(operations.get(2).path("status").asText()).isEqualTo("APPLIED");
    }

    @Test
    void rollbackRestoresTheValueTakenFromTheJournal() throws Exception {
        Fixture fixture = fixture();
        UUID request = idOf(submitRequest(
                fixture,
                fixture.player,
                body(
                        "CHARACTER_EDIT",
                        changeSet(
                                fieldOperation(fixture.characterId, "седой"),
                                historyOperation(fixture.characterId, "новая открытая история")),
                        null)));
        call(
                post("/games/" + fixture.gameId + "/requests/" + request + "/decision"),
                fixture.master.token,
                Map.of("approve", List.of(1, 2)));

        MvcResult rolledBack = call(
                post("/games/" + fixture.gameId + "/requests/" + request + "/rollback"),
                fixture.master.token,
                null);

        assertThat(rolledBack.getResponse().getStatus()).isEqualTo(200);
        assertThat(fixture.fieldOf("appearance"))
                .as("откат применяет старое значение из журнала (D-138)")
                .isEqualTo("высокий");
        assertThat(fixture.fieldOf("public_history")).isEqualTo("открыто");
        assertThat(json.readTree(bodyOf(rolledBack)).path("status").asText())
                .as("после отката применённых не осталось")
                .isEqualTo("REJECTED");
        assertThat(eventCount(fixture.gameId, "REQUEST_ROLLED_BACK"))
                .as("откат — факт истории со своим типом события (D-134)")
                .isEqualTo(1);
    }

    @Test
    void rollbackOfAnIrreversibleOperationIsRefused() throws Exception {
        Fixture fixture = fixture();
        UUID request = idOf(submitRequest(
                fixture,
                fixture.player,
                body(
                        "CHARACTER_EDIT",
                        changeSet(itemOperation(fixture.storageId, "Заявка-меч")),
                        null)));
        call(
                post("/games/" + fixture.gameId + "/requests/" + request + "/decision"),
                fixture.master.token,
                Map.of("approve", List.of(1)));

        MvcResult refused = call(
                post("/games/" + fixture.gameId + "/requests/" + request + "/rollback"),
                fixture.master.token,
                null);

        assertThat(refused.getResponse().getStatus())
                .as("удаление запрещено, поэтому создание предмета необратимо (D-010, D-138)")
                .isEqualTo(409);
        assertThat(json.readTree(bodyOf(refused)).path("code").asText())
                .isEqualTo("operation_not_reversible");
        assertThat(fixture.itemCount())
                .as("отказ отката ничего не удалил: предмет остался")
                .isEqualTo(1);
    }

    @Test
    void repeatedSubmissionWithTheSameKeyReturnsTheSameRequest() throws Exception {
        Fixture fixture = fixture();
        String key = "ключ-" + UUID.randomUUID();
        Map<String, Object> request =
                body("CHARACTER_EDIT", changeSet(fieldOperation(fixture.characterId, "седой")), key);

        UUID first = idOf(submitRequest(fixture, fixture.player, request));
        UUID second = idOf(submitRequest(fixture, fixture.player, request));

        assertThat(second)
                .as("повтор с тем же ключом не создаёт дубль (PLAN E14, задача 6)")
                .isEqualTo(first);
        assertThat(countRows(
                        "SELECT count(*) FROM request WHERE game_id = ? AND idempotency_key = ?",
                        fixture.gameId,
                        key))
                .isEqualTo(1);
    }

    @Test
    void invalidChangeSetIsStoredAsFailedWithAReason() throws Exception {
        Fixture fixture = fixture();
        Map<String, Object> badOperation = Map.of(
                "op", "delete_everything",
                "payload", Map.of());

        MvcResult submitted = submitRequest(
                fixture, fixture.player, body("CHARACTER_EDIT", changeSet(badOperation), null));

        JsonNode body = json.readTree(bodyOf(submitted));
        assertThat(submitted.getResponse().getStatus())
                .as("невалидный change set — не отказ запроса, а заявка со статусом FAILED (D-043)")
                .isEqualTo(200);
        assertThat(body.path("status").asText()).isEqualTo("FAILED");
        assertThat(body.path("error").asText()).contains("delete_everything");
        assertThat(body.path("operations")).isEmpty();
        assertThat(fixture.fieldOf("appearance")).isEqualTo("высокий");
    }

    @Test
    void onlyTheMasterDecides() throws Exception {
        Fixture fixture = fixture();
        UUID request = idOf(submitRequest(
                fixture,
                fixture.player,
                body("CHARACTER_EDIT", changeSet(fieldOperation(fixture.characterId, "седой")), null)));

        MvcResult refused = call(
                post("/games/" + fixture.gameId + "/requests/" + request + "/decision"),
                fixture.player.token,
                Map.of("approve", List.of(1)));

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(fixture.fieldOf("appearance"))
                .as("отказ в правах не применяет операцию")
                .isEqualTo("высокий");
    }

    @Test
    void playerSeesOnlyOwnRequestsInTheQueue() throws Exception {
        Fixture fixture = fixture();
        Account other = account("ДругойИгрок");
        mutations.joinOpenGame(other.userId, fixture.gameId);
        submitRequest(
                fixture,
                fixture.player,
                body("CHARACTER_EDIT", changeSet(fieldOperation(fixture.characterId, "седой")), null));

        MvcResult queue = call(get("/games/" + fixture.gameId + "/requests"), other.token, null);

        assertThat(json.readTree(bodyOf(queue)))
                .as("чужая заявка участнику не видна: очередь мастера — не публичная лента")
                .isEmpty();
    }

    @Test
    void masterSeesTheWholeQueue() throws Exception {
        Fixture fixture = fixture();
        submitRequest(
                fixture,
                fixture.player,
                body("CHARACTER_EDIT", changeSet(fieldOperation(fixture.characterId, "седой")), null));

        MvcResult queue = call(get("/games/" + fixture.gameId + "/requests"), fixture.master.token, null);

        assertThat(json.readTree(bodyOf(queue))).hasSize(1);
    }

    @Test
    void expiredRequestIsClosedByTheScheduler() throws Exception {
        Fixture fixture = fixture();
        UUID request = idOf(submitRequest(
                fixture,
                fixture.player,
                body(
                        "CHARACTER_EDIT",
                        changeSet(fieldOperation(fixture.characterId, "седой")),
                        null,
                        Instant.now().minusSeconds(60))));

        int expired = requests.expireDue();

        assertThat(expired).isGreaterThanOrEqualTo(1);
        assertThat(statusOf(request)).isEqualTo("EXPIRED");
        assertThat(fixture.fieldOf("appearance"))
                .as("просрочка — отказ, а не молчаливое применение")
                .isEqualTo("высокий");
        assertThat(eventCount(fixture.gameId, "REQUEST_EXPIRED")).isEqualTo(1);
    }

    @Test
    void expiredRequestCannotBeDecided() throws Exception {
        Fixture fixture = fixture();
        UUID request = idOf(submitRequest(
                fixture,
                fixture.player,
                body(
                        "CHARACTER_EDIT",
                        changeSet(fieldOperation(fixture.characterId, "седой")),
                        null,
                        Instant.now().minusSeconds(60))));

        MvcResult refused = call(
                post("/games/" + fixture.gameId + "/requests/" + request + "/decision"),
                fixture.master.token,
                Map.of("approve", List.of(1)));

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(statusOf(request)).isEqualTo("EXPIRED");
        assertThat(eventCount(fixture.gameId, "REQUEST_EXPIRED")).isEqualTo(1);
    }

    @Test
    void masterEditAndApprovedRequestKeepBothWrites() throws Exception {
        Fixture fixture = fixture();
        UUID request = idOf(submitRequest(
                fixture,
                fixture.player,
                body("CHARACTER_EDIT", changeSet(fieldOperation(fixture.characterId, "седой")), null)));

        // Правка карточки мастером и одобрение заявки на то же поле идут под блокировкой
        // строки персонажа, поэтому обе записи попадают в журнал, а не теряют друг друга.
        mutations.updateCharacterCard(
                fixture.master.userId,
                fixture.gameId,
                fixture.characterId,
                fixture.fieldOf("name"),
                null,
                "коротко стриженный",
                fixture.fieldOf("public_history"),
                fixture.fieldOf("private_history"),
                true,
                false);
        call(
                post("/games/" + fixture.gameId + "/requests/" + request + "/decision"),
                fixture.master.token,
                Map.of("approve", List.of(1)));

        assertThat(eventCount(fixture.gameId, "CHARACTER_CONTENT_CHANGED"))
                .as("обе правки одного поля оставили событие: ни одна не потеряна (D-034)")
                .isEqualTo(2);
        assertThat(fixture.fieldOf("appearance")).isEqualTo("седой");
    }

    private static Map<String, Object> historyOperation(UUID characterId, String value) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("characterId", characterId.toString());
        payload.put("field", "publicHistory");
        payload.put("value", value);
        Map<String, Object> operation = new java.util.LinkedHashMap<>();
        operation.put("op", "update_character_field");
        operation.put("payload", payload);
        return operation;
    }

    private static Map<String, Object> body(
            String type, Map<String, Object> payload, String idempotencyKey, Instant expiresAt) {
        Map<String, Object> request = body(type, payload, idempotencyKey);
        request.put("expiresAt", expiresAt.toString());
        return request;
    }
}
