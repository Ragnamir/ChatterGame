package com.chattergame.realtime;

import com.chattergame.message.FeedItem;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Конверт исходящих событий сокета (`PLAN` E09, задача 8). Собирается здесь, а не на месте
 * отправки, потому что разделение каналов (`D-035`) — часть контракта: мета-событие
 * игрового канала обязано не содержать ни фрагмента тела, ни имени говорящего, и это
 * свойство обязано быть видно в одном месте.
 *
 * <p>Поля мета-события закрыты списком: `chatId`, `seq`, `kind` плюс счётчик. Тело и
 * говорящий живут только в канале чата, который открывают подпиской.
 */
@Component
public class RealtimeEnvelope {

    private final ObjectMapper json;

    public RealtimeEnvelope(ObjectMapper json) {
        this.json = json;
    }

    /** Мета-событие игрового канала: номер, тип и счётчик; тела и имени говорящего нет (`D-035`). */
    public String meta(UUID gameId, UUID chatId, long seq, String kind, String eventType, long unread) {
        Map<String, Object> event = base("GAME", gameId, chatId, seq);
        event.put("kind", kind);
        event.put("eventType", eventType);
        event.put("unread", unread);
        return write(event);
    }

    /** Тело сообщения открытого чата: приходит только после подписки на этот чат (`D-035`). */
    public String chat(UUID gameId, FeedItem item) {
        Map<String, Object> event = base("CHAT", gameId, item.chatId(), item.seq());
        event.put("kind", item.kind().name());
        event.put("messageId", item.messageId());
        event.put("characterId", item.characterId());
        event.put("systemCode", item.systemCode());
        event.put("onBehalfOfMaster", item.onBehalfOfMaster());
        event.put("hiddenAuthor", item.hiddenAuthor());
        event.put("body", item.body());
        event.put("payload", item.payload());
        event.put("createdAt", item.createdAt());
        return write(event);
    }

    public String ready(UUID gameId, UUID userId) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("channel", "READY");
        event.put("gameId", gameId);
        event.put("userId", userId);
        return write(event);
    }

    public String subscribed(UUID gameId, UUID chatId, String subjectKind, UUID subjectId, String chatKind) {
        Map<String, Object> event = base("SUBSCRIBED", gameId, chatId, 0L);
        event.put("subjectKind", subjectKind);
        event.put("subjectId", subjectId);
        event.put("chatKind", chatKind);
        return write(event);
    }

    public String unsubscribed(UUID gameId, UUID chatId) {
        return write(base("UNSUBSCRIBED", gameId, chatId, 0L));
    }

    /** Изменение подписки при смене прав (`D-065`): сокет узнаёт о потере доступа без переподключения. */
    public String subscription(UUID gameId, UUID chatId, String status, String reason) {
        Map<String, Object> event = base("SUBSCRIPTION", gameId, chatId, 0L);
        event.put("status", status);
        event.put("reason", reason);
        return write(event);
    }

    public String resumed(UUID gameId, long lastSeq, int delivered) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("channel", "RESUMED");
        event.put("gameId", gameId);
        event.put("lastSeq", lastSeq);
        event.put("delivered", delivered);
        return write(event);
    }

    public String error(String code, String message) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("channel", "ERROR");
        event.put("code", code);
        event.put("message", message);
        return write(event);
    }

    public String pong() {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("channel", "PONG");
        event.put("at", Instant.now());
        return write(event);
    }

    /** Heartbeat: клиент отвечает `ping`, и этим отличает живой сокет от мёртвого (`D-083`). */
    public String heartbeat() {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("channel", "HEARTBEAT");
        event.put("at", Instant.now());
        return write(event);
    }

    private static Map<String, Object> base(String channel, UUID gameId, UUID chatId, long seq) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("channel", channel);
        event.put("gameId", gameId);
        event.put("chatId", chatId);
        event.put("seq", seq);
        return event;
    }

    private String write(Map<String, Object> event) {
        try {
            return json.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Событие сокета не сериализуется", e);
        }
    }
}
