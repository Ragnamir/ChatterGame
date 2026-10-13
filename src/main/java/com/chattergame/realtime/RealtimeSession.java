package com.chattergame.realtime;

import com.chattergame.chat.ChatKind;
import com.chattergame.message.InboxSubject;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Состояние одного сокета: кто смотрит, какие чаты открыты и что уже доставлено (`D-083`).
 *
 * <p>Дедупликация по `(chat_id, seq)` живёт здесь, а не в базе: при resume клиент может
 * прислать `last_seq` меньший, чем последнее доставленное, и повторы обязаны быть отсечены
 * на входе, иначе повторная доставка выглядит как новое сообщение.
 *
 * <p>Отправка синхронизирована: `WebSocketSession` не потокобезопасен, а пишут в него и
 * рассылка, и ответ на собственный запрос клиента.
 */
public final class RealtimeSession {

    /** Сколько недавних доставок помним. Старше — уже не повторить одним resume. */
    private static final int DELIVERED_MEMORY = 10_000;

    private final String id;
    private final UUID userId;
    private final UUID gameId;
    private final WebSocketSession socket;
    private final Map<UUID, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final Map<String, Boolean> delivered = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > DELIVERED_MEMORY;
        }
    };
    private final java.util.concurrent.atomic.AtomicLong lastSeenAt =
            new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());

    public RealtimeSession(String id, UUID userId, UUID gameId, WebSocketSession socket) {
        this.id = id;
        this.userId = userId;
        this.gameId = gameId;
        this.socket = socket;
    }

    /** Подписка на чат: чат, субъект, вид чата и открыт ли канал тела (`D-035`). */
    public record Subscription(UUID chatId, InboxSubject subject, ChatKind kind, boolean bodies) {
    }

    public String id() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public UUID gameId() {
        return gameId;
    }

    public boolean open() {
        return socket.isOpen();
    }

    public void subscribe(UUID chatId, InboxSubject subject, ChatKind kind, boolean bodies) {
        subscriptions.put(chatId, new Subscription(chatId, subject, kind, bodies));
    }

    public void unsubscribe(UUID chatId) {
        subscriptions.remove(chatId);
    }

    public Subscription subscription(UUID chatId) {
        return subscriptions.get(chatId);
    }

    public List<Subscription> subscriptions() {
        return List.copyOf(subscriptions.values());
    }

    /** true — строка ещё не доставлялась этому каналу сокета, можно слать. */
    public synchronized boolean firstDelivery(String channel, UUID chatId, long seq) {
        return delivered.putIfAbsent(channel + ":" + chatId + ":" + seq, Boolean.TRUE) == null;
    }

    public void send(String payload) throws IOException {
        synchronized (socket) {
            if (socket.isOpen()) {
                socket.sendMessage(new TextMessage(payload));
            }
        }
    }

    /**
     * Отметка входящей активности. Обновляется на принятое сообщение, а не на отправку:
     * иначе heartbeat продлевал бы жизнь сокету, который отвечать перестал, и мёртвое
     * соединение не было бы высвобождено никогда (`D-083`).
     */
    public void touch() {
        lastSeenAt.set(System.currentTimeMillis());
    }

    public long idleMillis(long now) {
        return now - lastSeenAt.get();
    }

    public void close() {
        try {
            socket.close();
        } catch (IOException e) {
            // закрытие уже мёртвого сокета не является ошибкой этапа
        }
    }
}
