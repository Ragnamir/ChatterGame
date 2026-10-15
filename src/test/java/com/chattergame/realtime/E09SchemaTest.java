package com.chattergame.realtime;

import com.chattergame.message.FeedItem;
import com.chattergame.message.MessageKind;
import com.chattergame.skeleton.ContractFiles;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Контракт событий сокета (`PLAN` E09, задача 8, `D-035`).
 *
 * <p>Проверка идёт с двух сторон: по схеме — какие каналы и поля вообще разрешены, и по
 * фактическому payload — что собранное конвертом событие схеме не противоречит. Одного
 * чтения схемы мало: она разрешает много, и утечка тела прошла бы мимо неё.
 */
class E09SchemaTest extends E09TestSupport {

    @Autowired
    private RealtimeEnvelope envelope;

    private final UUID gameId = UUID.randomUUID();
    private final UUID chatId = UUID.randomUUID();
    private final UUID characterId = UUID.randomUUID();

    /** Каждое событие, которое умеет собрать конверт, называет канал из схемы. */
    @Test
    void everyEnvelopeChannelIsDeclaredInTheSchema() throws Exception {
        JsonNode schema = json.readTree(ContractFiles.eventSchema());
        List<String> declared = new java.util.ArrayList<>();
        schema.at("/properties/channel/enum").forEach(node -> declared.add(node.asText()));

        List<String> emitted = List.of(
                channelOf(envelope.meta(gameId, chatId, 5L, "SPEECH", "MESSAGE_POSTED", 2L)),
                channelOf(envelope.chat(gameId, feedItem())),
                channelOf(envelope.ready(gameId, UUID.randomUUID())),
                channelOf(envelope.subscribed(gameId, chatId, "CHARACTER", characterId, "LOCATION")),
                channelOf(envelope.unsubscribed(gameId, chatId)),
                channelOf(envelope.subscription(gameId, chatId, "revoked", "access_changed")),
                channelOf(envelope.resumed(gameId, 4L, 1)),
                channelOf(envelope.error("not_a_member", "Нет доступа")),
                channelOf(envelope.pong()),
                channelOf(envelope.heartbeat()));

        assertThat(declared).containsAll(emitted);
        assertThat(emitted).doesNotHaveDuplicates();
    }

    /** Схема запрещает телу и имени говорящего появляться в мета-событии игрового канала (`D-035`). */
    @Test
    void schemaForbidsBodyOnTheGameChannel() throws Exception {
        JsonNode rule = json.readTree(ContractFiles.eventSchema()).at("/allOf/0");
        assertThat(rule.path("if").at("/properties/channel/const").asText()).isEqualTo("GAME");
        assertThat(rule.at("/then/not/anyOf").toString()).contains("body", "speakerName");
    }

    /** Собранное мета-событие не несёт ни тела, ни имени говорящего — не только по схеме. */
    @Test
    void metaEnvelopeActuallyCarriesNoBody() throws Exception {
        JsonNode meta = json.readTree(
                envelope.meta(gameId, chatId, 7L, "SPEECH", "MESSAGE_POSTED", 3L));

        assertThat(meta.has("body")).isFalse();
        assertThat(meta.has("speakerName")).isFalse();
        assertThat(meta.path("channel").asText()).isEqualTo("GAME");
        assertThat(meta.path("seq").asLong()).isEqualTo(7L);
        assertThat(meta.path("unread").asLong()).isEqualTo(3L);
        assertThat(meta.path("kind").asText()).isEqualTo("SPEECH");
    }

    /** Канал открытого чата несёт тело: без него подписка не отличалась бы от мета-канала. */
    @Test
    void chatEnvelopeCarriesTheBody() throws Exception {
        FeedItem item = feedItem();
        JsonNode chat = json.readTree(envelope.chat(gameId, item));

        assertThat(chat.path("channel").asText()).isEqualTo("CHAT");
        assertThat(chat.path("body").asText()).isEqualTo("Тело реплики");
        assertThat(chat.path("kind").asText()).isEqualTo("SPEECH");
        assertThat(chat.path("messageId").asText()).isEqualTo(item.messageId().toString());
    }

    /** Снапшот схемы — тот же файл: изменение контракта без обновления снапшота видит гейт. */
    @Test
    void schemaSnapshotMatchesTheLiveContract() throws Exception {
        assertThat(ContractFiles.eventSchema()).isEqualTo(ContractFiles.eventSchemaSnapshot());
        JsonNode snapshot = json.readTree(ContractFiles.eventSchemaSnapshot());
        assertThat(snapshot.at("/properties/channel/enum").toString())
                .as("каналы сокета зафиксированы в снапшоте")
                .contains("GAME", "CHAT", "RESUMED", "HEARTBEAT");
    }

    private String channelOf(String event) throws Exception {
        return json.readTree(event).path("channel").asText();
    }

    private FeedItem feedItem() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("hiddenAuthor", false);
        return new FeedItem(
                7L,
                MessageKind.SPEECH,
                UUID.randomUUID(),
                chatId,
                characterId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                false,
                false,
                "Тело реплики",
                null,
                payload,
                Instant.parse("2026-10-13T09:00:00Z"),
                List.of());
    }
}
