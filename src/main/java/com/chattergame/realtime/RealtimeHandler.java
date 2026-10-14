package com.chattergame.realtime;

import com.chattergame.chat.Chat;
import com.chattergame.message.MessageRepository;
import com.chattergame.message.InboxSubject;
import com.chattergame.message.MessageKind;
import com.chattergame.message.MessageService;
import com.chattergame.util.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.List;
import java.util.UUID;

/**
 * Обработчик сокета (`PLAN` E09, задачи 2–5, 7). Протокол — собственные JSON-события,
 * без STOMP (`REQUIREMENTS §21`): нужен минимум полей и полный контроль над тем, что
 * уходит в мета-канал.
 *
 * <p>Права не кэшируются при подписке (`D-065`): каждое сообщение проходит через
 * {@code MessageService.post}, а тот зовёт {@code assertCanPost} заново. Снять персонажа,
 * замьютить или завершить игру можно на живом сокете, и следующая запись обязана
 * получить отказ без переподключения.
 *
 * <p>Путь содержит игру: сокет принадлежит одной игре (`D-072`), поэтому `seq` — одна
 * непрерывная последовательность, и resume идёт по одному `last_seq`, а не по словарю.
 */
@Component
public class RealtimeHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(RealtimeHandler.class);

    private final RealtimeSessions sessions;
    private final RealtimeQueryService reads;
    private final RealtimeEnvelope envelope;
    private final RealtimeDispatcher dispatcher;
    private final MessageService messages;
    private final ObjectMapper json;

    public RealtimeHandler(
            RealtimeSessions sessions,
            RealtimeQueryService reads,
            RealtimeEnvelope envelope,
            RealtimeDispatcher dispatcher,
            MessageService messages,
            ObjectMapper json) {
        this.sessions = sessions;
        this.reads = reads;
        this.envelope = envelope;
        this.dispatcher = dispatcher;
        this.messages = messages;
        this.json = json;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession socket) throws Exception {
        UUID userId = (UUID) socket.getAttributes().get(SessionCookieHandshakeInterceptor.USER_ID);
        UUID gameId = gameIdOf(socket);
        RealtimeSession session = new RealtimeSession(socket.getId(), userId, gameId, socket);
        sessions.add(session);
        session.send(envelope.ready(gameId, userId));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
        sessions.remove(socket.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession socket, TextMessage message) throws Exception {
        RealtimeSession session = sessions.byId(socket.getId());
        if (session == null) {
            return;
        }
        session.touch();
        JsonNode request;
        try {
            request = json.readTree(message.getPayload());
        } catch (Exception e) {
            session.send(envelope.error("bad_request", "Некорректное событие сокета"));
            return;
        }
        try {
            handleRequest(session, request);
        } catch (ApiException e) {
            session.send(envelope.error(e.code(), e.getMessage()));
        } catch (RuntimeException e) {
            log.warn("Событие сокета не обработано: {}", e.toString());
            session.send(envelope.error("internal_error", "Событие не обработано"));
        }
    }

    private void handleRequest(RealtimeSession session, JsonNode request) throws Exception {
        String type = request.path("type").asText("");
        switch (type) {
            case "subscribe" -> subscribe(session, request);
            case "unsubscribe" -> unsubscribe(session, request);
            case "post" -> post(session, request);
            case "resume" -> resume(session, request);
            case "ping" -> session.send(envelope.pong());
            default -> session.send(envelope.error("unknown_type", "Неизвестный тип события"));
        }
    }

    private void subscribe(RealtimeSession session, JsonNode request) throws Exception {
        UUID chatId = requiredUuid(request, "chatId");
        UUID asCharacter = optionalUuid(request, "asCharacter");
        boolean bodies = !request.has("bodies") || request.path("bodies").asBoolean(true);
        Chat chat = reads.requireChat(session.gameId(), chatId);
        InboxSubject subject =
                reads.resolveSubjectForChat(session.userId(), session.gameId(), chatId, asCharacter);
        session.subscribe(chatId, subject, chat.kind(), bodies);
        session.send(envelope.subscribed(
                session.gameId(), chatId, subject.kind().name(), subject.id(), chat.kind().name()));
    }

    private void unsubscribe(RealtimeSession session, JsonNode request) throws Exception {
        UUID chatId = requiredUuid(request, "chatId");
        session.unsubscribe(chatId);
        session.send(envelope.unsubscribed(session.gameId(), chatId));
    }

    /**
     * Запись по сокету. Идёт тем же путём, что HTTP (`MessageService.post`), поэтому
     * проверка прав выполняется на каждое событие (`D-065`), а событие и строки
     * `inbox_entry` пишутся в одной транзакции (`D-034`).
     */
    private void post(RealtimeSession session, JsonNode request) throws Exception {
        UUID chatId = requiredUuid(request, "chatId");
        UUID asCharacter = optionalUuid(request, "asCharacter");
        MessageKind kind = MessageKind.valueOf(request.path("kind").asText("SPEECH"));
        String body = request.hasNonNull("body") ? request.get("body").asText() : null;
        boolean onBehalf = request.path("onBehalfOfMaster").asBoolean(false);
        messages.post(session.userId(), session.gameId(), chatId, asCharacter, kind, body, onBehalf);
    }

    /**
     * Resume (`D-083`): недостающее с момента `last_seq` одним запросом. Доставка идёт
     * через ту же дедупликацию по `(chat_id, seq)`, поэтому повторный resume не даёт
     * дублей даже если клиент прислал заниженный номер.
     */
    private void resume(RealtimeSession session, JsonNode request) throws Exception {
        long lastSeq = request.path("lastSeq").asLong(0L);
        List<MessageRepository.FeedRow> rows =
                reads.resume(session.gameId(), session.subscriptions(), lastSeq);
        int delivered = 0;
        for (MessageRepository.FeedRow row : rows) {
            if (session.subscription(row.item().chatId()) == null) {
                continue;
            }
            if (dispatcher.deliver(session, session.gameId(), row.item(), row.eventType())) {
                delivered++;
            }
        }
        session.send(envelope.resumed(session.gameId(), lastSeq, delivered));
    }

    /** Игра берётся из пути `/games/{gameId}/ws`: сокет не спрашивает её у клиента. */
    private static UUID gameIdOf(WebSocketSession socket) {
        String path = socket.getUri().getPath();
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("games".equals(parts[i])) {
                return UUID.fromString(parts[i + 1]);
            }
        }
        throw new IllegalStateException("Сокет открыт вне игры: " + path);
    }

    private static UUID requiredUuid(JsonNode request, String field) {
        UUID value = optionalUuid(request, field);
        if (value == null) {
            throw ApiException.badRequest("field_required", "Поле обязательно: " + field);
        }
        return value;
    }

    private static UUID optionalUuid(JsonNode request, String field) {
        if (!request.hasNonNull(field)) {
            return null;
        }
        return UUID.fromString(request.get(field).asText());
    }
}
