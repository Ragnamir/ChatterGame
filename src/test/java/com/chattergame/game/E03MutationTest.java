package com.chattergame.game;

import com.chattergame.user.E02TestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Мутации игры через `GameMutationService` (`PLAN` E03, задачи 3–7).
 *
 * <p>Тесты идут через HTTP, потому что проверяют сразу три вещи: права (кто может),
 * состояния ответа и записи в БД (что осталось после). Прямой вызов сервиса пропустил бы
 * первую.
 */
class E03MutationTest extends E02TestSupport {

    @Autowired
    private GameMutationService mutations;

    @Autowired
    private GameQueryService queries;

    @Test
    void creatingAGameLeavesCreatedEventAndMasterMembership() throws Exception {
        String login = unique("Мастер");
        String token = registerAndGetToken(login, PASSWORD);

        MvcResult result = call(post("/games"), token, Map.of(
                "title", "Тайна старого маяка",
                "publicDescription", "Публичное",
                "privateDescription", "Только мастеру"));

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        UUID gameId = UUID.fromString(readField(result, "id"));

        assertThat(countRows(
                        "SELECT count(*) FROM membership WHERE game_id = ? AND role = 'MASTER' AND status = 'JOINED'",
                        gameId))
                .as("мастер участвует сразу, иначе он не пройдёт проверку прав (REQUIREMENTS §6)")
                .isEqualTo(1);
        assertThat(eventTypes(gameId))
                .as("создание — мутация мира, значит обязана оставить событие (D-033)")
                .containsExactly("GAME_CREATED");
        assertThat(jdbc.queryForObject(
                        "SELECT last_seq FROM game_stream WHERE game_id = ?", Long.class, gameId))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                        "SELECT readiness FROM game WHERE id = ?", String.class, gameId))
                .as("игра рождается черновиком: значение по умолчанию колонки в 001-baseline.yaml")
                .isEqualTo("DRAFT");
    }

    @Test
    void everyMutationLeavesExactlyOneEventAndMovesTheCounter() throws Exception {
        Fixture fixture = masterWithGame();

        call(post("/games/" + fixture.gameId() + "/transitions"), fixture.token(),
                Map.of("transition", "MARK_READY"));
        call(patch("/games/" + fixture.gameId()), fixture.token(),
                Map.of("title", "Новое название"));
        call(post("/games/" + fixture.gameId() + "/transitions"), fixture.token(),
                Map.of("transition", "START"));
        call(post("/games/" + fixture.gameId() + "/transitions"), fixture.token(),
                Map.of("transition", "PAUSE", "pauseReason", "мастер ушёл на два дня"));
        call(post("/games/" + fixture.gameId() + "/transitions"), fixture.token(),
                Map.of("transition", "RESUME"));
        call(post("/games/" + fixture.gameId() + "/transitions"), fixture.token(),
                Map.of("transition", "FINISH"));

        assertThat(eventTypes(fixture.gameId())).containsExactly(
                "GAME_CREATED",
                "GAME_READINESS_CHANGED",
                "GAME_CONTENT_CHANGED",
                "GAME_STARTED",
                "GAME_PAUSED",
                "GAME_RESUMED",
                "GAME_FINISHED");

        List<Long> seqs = jdbc.queryForList(
                "SELECT seq FROM game_event WHERE game_id = ? ORDER BY seq",
                Long.class,
                fixture.gameId());
        assertThat(seqs)
                .as("номер выдаётся подряд и без дыр: иначе resume и экспорт теряют события")
                .isEqualTo(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L));
        assertThat(jdbc.queryForObject(
                        "SELECT last_seq FROM game_stream WHERE game_id = ?", Long.class, fixture.gameId()))
                .as("счётчик не отстаёт от журнала")
                .isEqualTo(7L);
        assertThat(countRows(
                        "SELECT count(*) FROM game_event WHERE game_id = ?", fixture.gameId()))
                .as("на каждую мутацию ровно одно событие, лишних нет")
                .isEqualTo(7);
    }

    /**
     * Отклонённый переход не оставляет ни события, ни нового состояния. Иначе журнал
     * описывал бы то, чего не случилось, и экспорт воспроизводил бы несуществующий мир.
     */
    @Test
    void refusedTransitionLeavesNeitherEventNorStateChange() throws Exception {
        Fixture fixture = masterWithGame();

        MvcResult refused = call(post("/games/" + fixture.gameId() + "/transitions"),
                fixture.token(), Map.of("transition", "START"));

        assertThat(refused.getResponse().getStatus())
                .as("старт невозможен из черновика (REQUIREMENTS §4)")
                .isEqualTo(409);
        assertThat(readField(refused, "code")).isEqualTo("start_not_allowed");
        assertThat(eventTypes(fixture.gameId()))
                .as("отказ — не мутация мира")
                .containsExactly("GAME_CREATED");
        assertThat(jdbc.queryForObject(
                        "SELECT phase FROM game WHERE id = ?", String.class, fixture.gameId()))
                .isEqualTo("NOT_STARTED");
    }

    @Test
    void nonMasterCannotMutateTheWorld() throws Exception {
        Fixture fixture = masterWithGame();
        String strangerLogin = unique("Чужак");
        String strangerToken = registerAndGetToken(strangerLogin, PASSWORD);

        MvcResult forbidden = call(post("/games/" + fixture.gameId() + "/transitions"),
                strangerToken, Map.of("transition", "MARK_READY"));

        assertThat(forbidden.getResponse().getStatus()).isEqualTo(403);
        assertThat(readField(forbidden, "code")).isEqualTo("not_a_member");
        assertThat(eventTypes(fixture.gameId())).containsExactly("GAME_CREATED");
    }

    /** Наблюдатель читает игру, но не меняет её (`D-063`). */
    @Test
    void observerReadsButDoesNotMutate() throws Exception {
        Fixture fixture = masterWithGame();
        UUID observerId = registerUser(unique("Наблюдатель"));
        mutations.addMembership(
                fixture.userId(), fixture.gameId(), observerId, MembershipRole.OBSERVER, MembershipStatus.JOINED);
        String observerToken = tokenForUser(observerId);

        assertThat(call(get("/games/" + fixture.gameId()), observerToken, null)
                        .getResponse().getStatus())
                .as("наблюдатель читает открытую часть игры (D-063)")
                .isEqualTo(200);
        assertThat(call(post("/games/" + fixture.gameId() + "/transitions"), observerToken,
                        Map.of("transition", "MARK_READY"))
                        .getResponse().getStatus())
                .as("но менять мир не может")
                .isEqualTo(403);
    }

    /** `FINISHED` — read-only: описания больше не правятся (`D-094`). */
    @Test
    void finishedGameRefusesContentChanges() throws Exception {
        Fixture fixture = masterWithGame();
        call(post("/games/" + fixture.gameId() + "/transitions"), fixture.token(),
                Map.of("transition", "MARK_READY"));
        call(post("/games/" + fixture.gameId() + "/transitions"), fixture.token(),
                Map.of("transition", "START"));
        call(post("/games/" + fixture.gameId() + "/transitions"), fixture.token(),
                Map.of("transition", "FINISH"));

        MvcResult refused = call(patch("/games/" + fixture.gameId()), fixture.token(),
                Map.of("publicDescription", "правка задним числом"));

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(readField(refused, "code")).isEqualTo("content_change_after_finish");
        assertThat(countRows(
                        "SELECT count(*) FROM game_event WHERE game_id = ? AND type = 'GAME_CONTENT_CHANGED'",
                        fixture.gameId()))
                .isZero();
    }

    /**
     * Номер события выдаётся под `SELECT … FOR UPDATE` на счётчике. Без блокировки десять
     * параллельных мутаций дают дубли и дыры, и журнал перестаёт воспроизводить мир.
     */
    @Test
    void concurrentMutationsProduceUniqueSequentialNumbers() throws Exception {
        Fixture fixture = masterWithGame();
        int threads = 10;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int index = i;
                tasks.add(() -> call(patch("/games/" + fixture.gameId()), fixture.token(),
                                Map.of("publicDescription", "правка " + index))
                        .getResponse().getStatus() + "");
            }
            List<Future<String>> results = pool.invokeAll(tasks);
            for (Future<String> result : results) {
                assertThat(result.get())
                        .as("параллельная правка должна проходить, а не падать на гонке")
                        .isEqualTo("200");
            }
        } finally {
            pool.shutdownNow();
        }

        List<Long> seqs = jdbc.queryForList(
                "SELECT seq FROM game_event WHERE game_id = ? ORDER BY seq",
                Long.class,
                fixture.gameId());
        Set<Long> unique = new HashSet<>(seqs);
        assertThat(unique)
                .as("дубли номеров недопустимы: два события с одним seq ломают resume")
                .hasSize(seqs.size());
        assertThat(seqs)
                .as("номера идут подряд от 1 без дыр")
                .isEqualTo(java.util.stream.LongStream
                        .rangeClosed(1, seqs.size())
                        .boxed()
                        .toList());
        assertThat(seqs).hasSize(threads + 1);
    }

    /** Архивация — единственный путь снять игру с глаз; удаления не существует (`D-010`). */
    @Test
    void archivingKeepsTheGameAndWritesTheEvent() throws Exception {
        Fixture fixture = masterWithGame();

        assertThat(mutations.archiveGame(fixture.userId(), fixture.gameId(), "мастер закрыл игру"))
                .isTrue();
        assertThat(mutations.archiveGame(fixture.userId(), fixture.gameId(), "мастер закрыл игру"))
                .as("повторная архивация идемпотентна")
                .isFalse();

        assertThat(countRows("SELECT count(*) FROM game WHERE id = ?", fixture.gameId()))
                .as("игра не удаляется никогда (D-010)")
                .isEqualTo(1);
        assertThat(eventTypes(fixture.gameId()))
                .containsExactlyInAnyOrder("GAME_CREATED", "GAME_ARCHIVED");
        assertThat(jdbc.queryForObject(
                        "SELECT archived_reason FROM game WHERE id = ?", String.class, fixture.gameId()))
                .isEqualTo("мастер закрыл игру");
    }

    /** Аудит-чтение: страница от курсора, фильтр по типу, лимит зажат сверху (`D-082`). */
    @Test
    void auditReadingPagesFromTheLastSeenSequence() throws Exception {
        Fixture fixture = masterWithGame();
        call(patch("/games/" + fixture.gameId()), fixture.token(), Map.of("title", "Раз"));
        call(patch("/games/" + fixture.gameId()), fixture.token(), Map.of("title", "Два"));

        assertThat(queries.events(fixture.userId(), fixture.gameId(), 1, null, 100))
                .as("курсор `after` не повторяет уже увиденное")
                .hasSize(2);
        assertThat(queries.events(fixture.userId(), fixture.gameId(), 0, null, 2))
                .as("лимит ограничивает страницу")
                .hasSize(2);
        assertThat(queries.events(
                        fixture.userId(), fixture.gameId(), 0, com.chattergame.journal.GameEventType.GAME_CREATED, 100))
                .as("фильтр по типу оставляет только нужные события")
                .hasSize(1);
        assertThat(queries.events(fixture.userId(), fixture.gameId(), 0, null, 10_000))
                .as("клиент не может запросить журнал целиком")
                .hasSizeLessThanOrEqualTo(GameQueryService.MAX_PAGE);
    }

    /** События читаются в порядке игрового номера, а не в порядке вставки. */
    @Test
    void auditReadingOrdersBySequence() throws Exception {
        Fixture fixture = masterWithGame();
        call(patch("/games/" + fixture.gameId()), fixture.token(), Map.of("title", "Раз"));
        call(patch("/games/" + fixture.gameId()), fixture.token(), Map.of("title", "Два"));

        assertThat(queries.events(fixture.userId(), fixture.gameId(), 0, null, 100))
                .extracting(com.chattergame.journal.GameEvent::seq)
                .isSorted();
    }

    // --- обвязка -----------------------------------------------------------------------

    private record Fixture(UUID gameId, UUID userId, String token) {
    }

    private Fixture masterWithGame() throws Exception {
        String login = unique("Мастер");
        String token = registerAndGetToken(login, PASSWORD);
        MvcResult created = call(post("/games"), token, Map.of("title", "Игра " + unique("С")));
        UUID gameId = UUID.fromString(readField(created, "id"));
        UUID userId = jdbc.queryForObject(
                "SELECT id FROM app_user WHERE login_normalized = ?", UUID.class, loginNormalized(login));
        return new Fixture(gameId, userId, token);
    }

    private UUID registerUser(String login) throws Exception {
        register(login, OTHER_PASSWORD, null);
        return jdbc.queryForObject(
                "SELECT id FROM app_user WHERE login_normalized = ?", UUID.class, loginNormalized(login));
    }

    private String tokenForUser(UUID userId) throws Exception {
        String login = jdbc.queryForObject("SELECT login FROM app_user WHERE id = ?", String.class, userId);
        return loginAndGetToken(login, OTHER_PASSWORD);
    }

    private List<String> eventTypes(UUID gameId) {
        return jdbc.queryForList(
                "SELECT type FROM game_event WHERE game_id = ? ORDER BY seq", String.class, gameId);
    }

    private String readField(MvcResult result, String field) throws Exception {
        return json.readTree(bodyOf(result)).get(field).asText();
    }
}
