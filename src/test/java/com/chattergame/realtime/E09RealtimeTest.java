package com.chattergame.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Двухуровневая подписка и живая доставка (`PLAN` E09, задачи 2–4, 7).
 *
 * <p>Тесты идут через настоящие сокеты и HTTP-запись: предмет этапа — то, что доставка
 * действительно происходит, а не то, что метод вызван. Поэтому мир строится обычными
 * ручками, а наблюдение ведётся с двух клиентов одновременно.
 */
class E09RealtimeTest extends E09TestSupport {

    /**
     * Критерий приёмки по `D-035`: мета-событие игрового канала не несёт ни тела, ни имени
     * говорящего. Проверка двойная — по полям события и по отсутствию подстроки тела в
     * самом payload: второе ловит утечку через `payload`, которое полями не видно.
     */
    @Test
    void metaEventCarriesNeitherBodyNorSpeakerName() {
        Fixture fixture = runningGame();
        try (Socket player = open(fixture.gameId(), fixture.playerToken(), ORIGIN)) {
            player.awaitChannel("READY");
            player.subscribe(fixture.locationId(), fixture.playerCharacter(), false);
            player.awaitChannel("SUBSCRIBED");

            String secret = "СЕКРЕТ-" + unique("");
            assertThat(postMessage(fixture, fixture.masterToken(), fixture.masterCharacter(), secret).status())
                    .isEqualTo(201);

            JsonNode meta = player.awaitChannel("GAME");
            assertThat(meta.get("chatId").asText()).isEqualTo(fixture.locationId().toString());
            assertThat(meta.get("seq").asLong()).isPositive();
            assertThat(meta.get("kind").asText()).isEqualTo("SPEECH");
            assertThat(meta.get("eventType").asText()).isEqualTo("MESSAGE_POSTED");
            assertThat(meta.get("unread").asLong()).isPositive();

            assertThat(meta.has("body")).as("мета-событие не содержит тела (D-035)").isFalse();
            assertThat(meta.has("speakerName")).as("мета-событие не содержит имени говорящего (D-035)").isFalse();
            assertThat(meta.toString())
                    .as("подстрока тела не должна встречаться в мета-событии ни в каком поле")
                    .doesNotContain(secret);
        }
    }

    /** Тело приходит только на открытый канал чата; закрытый канал получает те же мета-события. */
    @Test
    void bodiesArriveOnlyOnTheOpenChatChannel() {
        Fixture fixture = runningGame();
        try (Socket withBodies = open(fixture.gameId(), fixture.playerToken(), ORIGIN);
                Socket metaOnly = open(fixture.gameId(), fixture.masterToken(), ORIGIN)) {
            withBodies.awaitChannel("READY");
            metaOnly.awaitChannel("READY");
            withBodies.subscribe(fixture.locationId(), fixture.playerCharacter(), true);
            metaOnly.subscribe(fixture.locationId(), fixture.masterCharacter(), false);
            withBodies.awaitChannel("SUBSCRIBED");
            metaOnly.awaitChannel("SUBSCRIBED");

            String text = "Реплика " + unique("");
            postMessage(fixture, fixture.masterToken(), fixture.masterCharacter(), text);

            JsonNode body = withBodies.awaitChannel("CHAT");
            assertThat(body.get("body").asText()).isEqualTo(text);
            assertThat(body.get("messageId").asText()).isNotEmpty();

            JsonNode meta = metaOnly.awaitChannel("GAME");
            assertThat(meta.get("seq").asLong()).isEqualTo(body.get("seq").asLong());
            assertThat(metaOnly.drain(300))
                    .as("канал без тела не получает ни одного события CHAT (D-035)")
                    .noneMatch(event -> "CHAT".equals(event.path("channel").asText()));
        }
    }

    /** Запись по сокету идёт тем же путём, что HTTP: сообщение видно в ленте и второму клиенту. */
    @Test
    void postOverSocketLandsInTheFeedAndReachesTheOtherClient() {
        Fixture fixture = runningGame();
        try (Socket player = open(fixture.gameId(), fixture.playerToken(), ORIGIN);
                Socket master = open(fixture.gameId(), fixture.masterToken(), ORIGIN)) {
            player.awaitChannel("READY");
            master.awaitChannel("READY");
            player.subscribe(fixture.locationId(), fixture.playerCharacter(), true);
            master.subscribe(fixture.locationId(), fixture.masterCharacter(), true);
            player.awaitChannel("SUBSCRIBED");
            master.awaitChannel("SUBSCRIBED");

            String text = "С сокета " + unique("");
            player.post(fixture.locationId(), fixture.playerCharacter(), text);

            JsonNode received = master.await(event -> "CHAT".equals(event.path("channel").asText())
                    && text.equals(event.path("body").asText()));
            assertThat(received.get("characterId").asText())
                    .isEqualTo(fixture.playerCharacter().toString());

            JsonNode feed = get("/games/" + fixture.gameId() + "/chats/" + fixture.locationId()
                    + "/messages?asCharacter=" + fixture.playerCharacter(), fixture.masterToken()).body();
            assertThat(feed.toString())
                    .as("сообщение сокета лежит в общей ленте, а не во втором хранилище")
                    .contains(text);
        }
    }

    /**
     * Права проверяются на каждое событие (`D-065`): запрет, наложенный на живом сокете,
     * отказывает без переподключения, а снятие запрета возвращает запись.
     */
    @Test
    void restrictionRefusesPostingOnAnOpenSocketWithoutReconnect() {
        Fixture fixture = runningGame();
        try (Socket player = open(fixture.gameId(), fixture.playerToken(), ORIGIN)) {
            player.awaitChannel("READY");
            player.subscribe(fixture.locationId(), fixture.playerCharacter(), true);
            player.awaitChannel("SUBSCRIBED");

            player.post(fixture.locationId(), fixture.playerCharacter(), "До запрета " + unique(""));
            player.awaitChannel("CHAT");

            Response restriction = post("/games/" + fixture.gameId() + "/restrictions",
                    fixture.masterToken(),
                    java.util.Map.of(
                            "subjectKind", "CHARACTER",
                            "subjectId", fixture.playerCharacter().toString(),
                            "kind", "NO_SPEECH",
                            "reason", "Голос пропал"));
            assertThat(restriction.status()).as("запрет наложен: %s", restriction.body()).isEqualTo(201);

            player.post(fixture.locationId(), fixture.playerCharacter(), "После запрета " + unique(""));
            JsonNode refusal = player.await(event -> "ERROR".equals(event.path("channel").asText()));
            assertThat(refusal.get("code").asText()).isEqualTo("speech_restricted");

            String restrictionId = restriction.field("id");
            assertThat(post("/games/" + fixture.gameId() + "/restrictions/" + restrictionId + "/revoke",
                    fixture.masterToken(), java.util.Map.of()).status()).isEqualTo(200);

            String allowed = "После снятия " + unique("");
            player.post(fixture.locationId(), fixture.playerCharacter(), allowed);
            JsonNode body = player.await(event -> "CHAT".equals(event.path("channel").asText())
                    && allowed.equals(event.path("body").asText()));
            assertThat(body.get("kind").asText()).isEqualTo("SPEECH");
        }
    }

    /** Завершение игры отказывает в записи на уже открытом сокете (`D-065`, `D-094`). */
    @Test
    void finishingTheGameRefusesPostingOnAnOpenSocket() {
        Fixture fixture = runningGame();
        try (Socket player = open(fixture.gameId(), fixture.playerToken(), ORIGIN)) {
            player.awaitChannel("READY");
            player.subscribe(fixture.locationId(), fixture.playerCharacter(), true);
            player.awaitChannel("SUBSCRIBED");

            assertThat(post("/games/" + fixture.gameId() + "/transitions", fixture.masterToken(),
                    java.util.Map.of("transition", "FINISH")).status()).isEqualTo(200);

            player.post(fixture.locationId(), fixture.playerCharacter(), "После финала " + unique(""));
            JsonNode refusal = player.await(event -> "ERROR".equals(event.path("channel").asText()));
            assertThat(refusal.get("code").asText()).isEqualTo("game_finished");
        }
    }

    /** Архив персонажа гасит подписку и сообщает об этом клиенту: молчание было бы утечкой (`D-065`). */
    @Test
    void archivingTheCharacterRevokesTheSubscription() {
        Fixture fixture = runningGame();
        try (Socket player = open(fixture.gameId(), fixture.playerToken(), ORIGIN)) {
            player.awaitChannel("READY");
            player.subscribe(fixture.locationId(), fixture.playerCharacter(), true);
            player.awaitChannel("SUBSCRIBED");

            assertThat(post("/games/" + fixture.gameId() + "/characters/"
                    + fixture.playerCharacter() + "/archive", fixture.masterToken(),
                    java.util.Map.of("reason", "Вышел из игры")).status()).isEqualTo(200);

            JsonNode revoked = player.awaitChannel("SUBSCRIPTION");
            assertThat(revoked.get("status").asText()).isEqualTo("revoked");
            assertThat(revoked.get("chatId").asText()).isEqualTo(fixture.locationId().toString());
        }
    }

    /** `ping` отвечается `pong`: клиент отличает живого сервера от молчащего посредника. */
    @Test
    void pingIsAnsweredWithPong() {
        Fixture fixture = runningGame();
        try (Socket player = open(fixture.gameId(), fixture.playerToken(), ORIGIN)) {
            player.awaitChannel("READY");
            player.ping();
            assertThat(player.awaitChannel("PONG")).isNotNull();
        }
    }

    /** Отписка закрывает канал тела, не закрывая сокет (`D-035`). */
    @Test
    void unsubscribeStopsBodyDeliveryWithoutClosingTheSocket() {
        Fixture fixture = runningGame();
        try (Socket player = open(fixture.gameId(), fixture.playerToken(), ORIGIN)) {
            player.awaitChannel("READY");
            player.subscribe(fixture.locationId(), fixture.playerCharacter(), true);
            player.awaitChannel("SUBSCRIBED");
            player.send(java.util.Map.of("type", "unsubscribe", "chatId", fixture.locationId().toString()));
            player.awaitChannel("UNSUBSCRIBED");

            postMessage(fixture, fixture.masterToken(), fixture.masterCharacter(), "После отписки " + unique(""));

            assertThat(player.drain(500))
                    .as("после отписки не приходит ни тела, ни мета-событий этого чата")
                    .noneMatch(event -> List.of("GAME", "CHAT").contains(event.path("channel").asText()));
            assertThat(player.closed()).as("сам сокет остаётся живым").isFalse();
        }
    }
}
