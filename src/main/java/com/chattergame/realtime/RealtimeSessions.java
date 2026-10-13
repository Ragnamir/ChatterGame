package com.chattergame.realtime;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Реестр живых сокетов (`PLAN` E09, задача 6). Heartbeat и высвобождение мёртвых сокетов
 * нужны затем же, зачем реестр: без учёта открытых соединений нечего проверять на
 * живость, а TCP-таймаут на мобильной сети приходит позже, чем истёк смысл доставки.
 */
@Component
public class RealtimeSessions {

    private final Map<String, RealtimeSession> sessions = new ConcurrentHashMap<>();

    public void add(RealtimeSession session) {
        sessions.put(session.id(), session);
    }

    public void remove(String sessionId) {
        sessions.remove(sessionId);
    }

    public RealtimeSession byId(String sessionId) {
        return sessions.get(sessionId);
    }

    /** Все живые сокеты: обход нужен heartbeat-у, а не рассылке (`D-083`). */
    public List<RealtimeSession> all() {
        return List.copyOf(sessions.values());
    }

    public List<RealtimeSession> forGame(UUID gameId) {
        return sessions.values().stream()
                .filter(session -> gameId.equals(session.gameId()))
                .toList();
    }

    /**
     * Высвобождение мёртвых сокетов: закрытые соединения не должны копить подписки,
     * иначе рассылка растёт вместе с числом переподключений за вечер.
     */
    public int releaseClosed() {
        int released = 0;
        for (RealtimeSession session : List.copyOf(sessions.values())) {
            if (!session.open()) {
                sessions.remove(session.id());
                released++;
            }
        }
        return released;
    }

    public int size() {
        return sessions.size();
    }
}
