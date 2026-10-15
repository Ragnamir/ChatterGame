package com.chattergame.game;

import com.chattergame.message.FeedItem;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Наблюдатель: что он видит и чего не видит (`PLAN` E13, задача 3; `D-129`).
 *
 * <p>Отбор идёт по истории, а не по типу чата: локация, куда ни разу не вводили играбельного
 * персонажа, наблюдателю не показывается, потому что частью рассказа ещё не стала. Поэтому
 * в проверке обязательно есть вторая, «пустая» локация: без неё правило «показывать только
 * историю» и правило «показывать всё» дают один и тот же зелёный.
 *
 * <p>Чтение наблюдателя идёт тем же путём, что у всех, — `inbox_entry` с субъектом-участием,
 * отдельного read-пути нет (`D-030`, `D-086`).
 */
class E13ObserverTest extends E13TestSupport {

    @Test
    void observerSeesOnlyLocationsWithPlayableHistory() throws Exception {
        Fixture fixture = observerGame();

        JsonNode graph = readGraph(fixture);

        List<String> titles = new ArrayList<>();
        graph.path("nodes").forEach(node -> titles.add(node.path("title").asText()));
        assertThat(titles)
                .as("локация с историей открыта, пустая — нет (D-129)")
                .contains("Площадь", "ТретийДвор")
                .doesNotContain("Причал");
    }

    @Test
    void observerSeesOnlyTransitionsBetweenVisibleLocations() throws Exception {
        Fixture fixture = observerGame();

        JsonNode graph = readGraph(fixture);

        UUID place = fixture.place;
        UUID third = thirdLocationOf(fixture);
        List<UUID> visibleEdges = new ArrayList<>();
        graph.path("edges").forEach(edge -> {
            if (edge.path("fromChatId").asText().equals(place.toString())
                    || edge.path("toChatId").asText().equals(place.toString())) {
                visibleEdges.add(UUID.fromString(edge.path("fromChatId").asText()));
            }
        });
        assertThat(graph.path("edges"))
                .as("ребро в место без истории наблюдателю не отдаётся (D-129)")
                .hasSize(1);
        assertThat(visibleEdges).containsExactly(place);
        assertThat(graph.path("edges").get(0).path("toChatId").asText())
                .isEqualTo(third.toString());
    }

    @Test
    void observerReadsTheOpenHistoryThroughInboxEntries() throws Exception {
        Fixture fixture = observerGame();
        Account watcher = fixture.watcher;
        UUID membershipId = fixture.membershipIdOf(watcher.userId);

        backfill.runNow();

        List<String> bodies = reads.feed(watcher.userId, fixture.gameId, fixture.place, null, 0, 0)
                .items().stream()
                .map(FeedItem::body)
                .filter(java.util.Objects::nonNull)
                .toList();
        assertThat(bodies)
                .as("наблюдатель видит открытую историю, а не пустые комнаты (§6)")
                .contains("реплика в площади");
        assertThat(inboxRows(membershipId, fixture.place)).isGreaterThan(0);
    }

    @Test
    void observerHasNoRowsForTheLocationWithoutHistory() throws Exception {
        Fixture fixture = observerGame();
        backfill.runNow();
        masterSays(fixture, fixture.elsewhere, "реплика в пустом причале");

        assertThat(inboxRows(fixture.membershipIdOf(fixture.watcher.userId), fixture.elsewhere))
                .as("пустая локация наблюдателю не адресуется вовсе")
                .isZero();
        assertThat(reads.feed(fixture.watcher.userId, fixture.gameId, fixture.elsewhere, null, 0, 0)
                .items())
                .isEmpty();
    }

    @Test
    void newMessagesInAnOpenedLocationReachTheObserverImmediately() throws Exception {
        Fixture fixture = observerGame();
        backfill.runNow();
        long before = inboxRows(fixture.membershipIdOf(fixture.watcher.userId), fixture.place);

        masterSays(fixture, fixture.place, "новая реплика в площади");

        assertThat(inboxRows(fixture.membershipIdOf(fixture.watcher.userId), fixture.place))
                .as("материализация при записи адресует наблюдателя без отдельного чтения (D-030)")
                .isEqualTo(before + 1);
    }

    /**
     * Публичная игра с тремя локациями и наблюдателем. Два слота заняты другими игроками:
     * свободный слот сделал бы входящего игроком, а не наблюдателем (`D-132`), и проверять
     * было бы нечего.
     */
    private Fixture observerGame() throws Exception {
        Fixture fixture = fixture();
        UUID third = mutations.createLocation(
                fixture.master.userId, fixture.gameId, "ТретийДвор", "Описание").id();
        // Место становится частью рассказа, если в нём побывал играбельный персонаж (`D-129`).
        // Персонаж проходит двор насквозь и возвращается: история остаётся, а свободных
        // слотов по-прежнему два, иначе третий вошедший стал бы игроком, а не наблюдателем.
        mutations.placeCharacter(fixture.master.userId, fixture.gameId, fixture.secondCharacter, third);
        mutations.placeCharacter(
                fixture.master.userId, fixture.gameId, fixture.secondCharacter, fixture.place);
        mutations.createTransition(
                fixture.master.userId, fixture.gameId, fixture.place, third, "Тропа во двор");
        mutations.createTransition(
                fixture.master.userId, fixture.gameId, fixture.place, fixture.elsewhere, "Спуск к причалу");
        fixture.makePublic();
        fixture.ready();
        fixture.start();
        masterSays(fixture, fixture.place, "реплика в площади");

        Account first = account("СлотПервый");
        Account second = account("СлотВторой");
        for (Account account : List.of(first, second)) {
            call(post("/games/" + fixture.gameId + "/memberships/join"), account.token, Map.of());
        }
        Account watcher = account("Наблюдатель");
        MvcResult joined = call(
                post("/games/" + fixture.gameId + "/memberships/join"), watcher.token, Map.of());
        assertThat(json.readTree(bodyOf(joined)).path("role").asText())
                .as("свободных слотов нет — наблюдатель (D-132)")
                .isEqualTo("OBSERVER");
        fixture.watcher = watcher;
        fixture.third = third;
        return fixture;
    }

    private UUID thirdLocationOf(Fixture fixture) {
        return fixture.third;
    }

    private JsonNode readGraph(Fixture fixture) throws Exception {
        MvcResult result = call(
                get("/games/" + fixture.gameId + "/graph"), fixture.watcher.token, null);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json.readTree(bodyOf(result));
    }
}
