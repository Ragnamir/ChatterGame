package com.chattergame.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Resume после разрыва (`PLAN` E09, задача 4, `D-083`).
 *
 * <p>Критерий приёмки звучит как «разрыв на 30 сообщений, восстановление — ровно
 * пропущенные, без дублей и потерь». Поэтому здесь действительно рвётся соединение:
 * проверка через вызов метода пропустила бы и порядок, и повторную доставку.
 */
class E09ResumeTest extends E09TestSupport {

    private static final int MISSED = 30;

    @Test
    void resumeAfterDisconnectGivesExactlyTheMissedMessagesWithoutDuplicates() {
        Fixture fixture = runningGame();
        long lastSeen;

        try (Socket first = open(fixture.gameId(), fixture.playerToken(), ORIGIN)) {
            first.awaitChannel("READY");
            first.subscribe(fixture.locationId(), fixture.playerCharacter(), true);
            first.awaitChannel("SUBSCRIBED");

            postMessage(fixture, fixture.masterToken(), fixture.masterCharacter(), "До разрыва " + unique(""));
            lastSeen = first.awaitChannel("GAME").get("seq").asLong();
        }

        for (int i = 0; i < MISSED; i++) {
            assertThat(postMessage(fixture, fixture.masterToken(), fixture.masterCharacter(),
                    "Пропущено " + i).status())
                    .as("сообщение %s записано, пока сокет закрыт", i)
                    .isEqualTo(201);
        }

        try (Socket second = open(fixture.gameId(), fixture.playerToken(), ORIGIN)) {
            second.awaitChannel("READY");
            second.subscribe(fixture.locationId(), fixture.playerCharacter(), true);
            second.awaitChannel("SUBSCRIBED");

            second.resume(lastSeen);
            List<JsonNode> events = second.drain(700);

            JsonNode resumed = events.stream()
                    .filter(event -> "RESUMED".equals(event.path("channel").asText()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Resume не подтвердился: " + events));
            assertThat(resumed.get("delivered").asLong())
                    .as("ровно пропущенное, ни строкой больше")
                    .isEqualTo(MISSED);

            List<Long> metaSeqs = events.stream()
                    .filter(event -> "GAME".equals(event.path("channel").asText()))
                    .map(event -> event.get("seq").asLong())
                    .toList();
            List<Long> bodySeqs = events.stream()
                    .filter(event -> "CHAT".equals(event.path("channel").asText()))
                    .map(event -> event.get("seq").asLong())
                    .toList();

            List<Long> expected = new ArrayList<>();
            for (long seq = lastSeen + 1; seq <= lastSeen + MISSED; seq++) {
                expected.add(seq);
            }
            assertThat(metaSeqs)
                    .as("доставлены ровно пропущенные мета-события, по порядку и без дублей")
                    .containsExactlyElementsOf(expected);
            assertThat(bodySeqs)
                    .as("тела приходят ровно для тех же строк и без дублей")
                    .containsExactlyElementsOf(expected);
            assertThat(new HashSet<>(metaSeqs)).hasSize(MISSED);
        }
    }

    /**
     * Повторный resume с уже подтверждённым номером не даёт дублей: дедупликация по
     * `(chat_id, seq)` живёт в сокете, а не в клиенте (`D-083`).
     */
    @Test
    void repeatedResumeDoesNotDuplicateAlreadyDeliveredRows() {
        Fixture fixture = runningGame();
        try (Socket player = open(fixture.gameId(), fixture.playerToken(), ORIGIN)) {
            player.awaitChannel("READY");
            player.subscribe(fixture.locationId(), fixture.playerCharacter(), true);
            player.awaitChannel("SUBSCRIBED");

            List<Long> posted = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                postMessage(fixture, fixture.masterToken(), fixture.masterCharacter(), "Строка " + i);
                posted.add(player.awaitChannel("GAME").get("seq").asLong());
            }
            long first = posted.get(0);

            player.resume(first);
            JsonNode resumed = player.awaitChannel("RESUMED");
            assertThat(resumed.get("delivered").asLong())
                    .as("строки, ушедшие живьём, не приходят второй раз по тому же курсору")
                    .isZero();

            player.resume(first);
            JsonNode again = player.awaitChannel("RESUMED");
            assertThat(again.get("delivered").asLong())
                    .as("повтор уже доставленного не считается доставкой")
                    .isZero();
        }
    }

    /** Resume без подписок — пустой ответ: сокет не выкачивает игру, к которой не подписан. */
    @Test
    void resumeWithoutSubscriptionsDeliversNothing() {
        Fixture fixture = runningGame();
        try (Socket player = open(fixture.gameId(), fixture.playerToken(), ORIGIN)) {
            player.awaitChannel("READY");
            player.resume(0L);
            JsonNode resumed = player.awaitChannel("RESUMED");
            assertThat(resumed.get("delivered").asLong()).isZero();
            assertThat(player.drain(300))
                    .noneMatch(event -> "CHAT".equals(event.path("channel").asText()));
        }
    }

    /** Resume уважает видимость (`D-030`): скрытый автор не приходит и этим путём. */
    @Test
    void resumeDeliversOnlyWhatTheSubjectMaySee() {
        Fixture fixture = runningGame();

        assertThat(put("/games/" + fixture.gameId() + "/characters/" + fixture.masterCharacter()
                + "/stealth", fixture.masterToken(),
                java.util.Map.of("hidden", true, "reason", "Крадётся")).status()).isEqualTo(200);
        String hidden = "Вне видимости " + unique("");
        assertThat(postMessage(fixture, fixture.masterToken(), fixture.masterCharacter(), hidden).status())
                .isEqualTo(201);

        try (Socket player = open(fixture.gameId(), fixture.playerToken(), ORIGIN)) {
            player.awaitChannel("READY");
            player.subscribe(fixture.locationId(), fixture.playerCharacter(), true);
            player.awaitChannel("SUBSCRIBED");
            player.resume(0L);
            player.awaitChannel("RESUMED");

            List<JsonNode> events = player.drain(500);
            assertThat(events.toString())
                    .as("реплика скрытого автора не приходит и через resume")
                    .doesNotContain(hidden);
        }
    }
}
