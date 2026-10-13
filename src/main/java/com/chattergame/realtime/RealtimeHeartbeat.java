package com.chattergame.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Heartbeat и высвобождение мёртвых сокетов (`PLAN` E09, задача 6, `D-083`).
 *
 * <p>Мобильная сеть рвёт соединение десятки раз за вечер, и часть разрывов не доходит
 * до сервера мгновенно: TCP узнаёт об обрыве по своему таймауту, который длиннее срока,
 * когда доставка уже потеряла смысл. Поэтому живость определяется приложением: молчащий
 * дольше `idleTimeout` сокет закрывается, а его подписки перестают участвовать в рассылке.
 */
@Component
public class RealtimeHeartbeat {

    private static final Logger log = LoggerFactory.getLogger(RealtimeHeartbeat.class);

    private final RealtimeSessions sessions;
    private final RealtimeEnvelope envelope;
    private final long idleTimeoutMs;

    public RealtimeHeartbeat(
            RealtimeSessions sessions, RealtimeEnvelope envelope, RealtimeProperties properties) {
        this.sessions = sessions;
        this.envelope = envelope;
        this.idleTimeoutMs = properties.getIdleTimeout().toMillis();
    }

    @Scheduled(fixedDelayString = "#{@realtimeProperties.heartbeatInterval.toMillis()}")
    public void beat() {
        sessions.releaseClosed();
        long now = System.currentTimeMillis();
        String ping = envelope.heartbeat();
        for (RealtimeSession session : sessions.all()) {
            if (session.idleMillis(now) > idleTimeoutMs) {
                session.close();
                sessions.remove(session.id());
                continue;
            }
            try {
                session.send(ping);
            } catch (IOException e) {
                log.debug("Heartbeat не доставлен в {}: {}", session.id(), e.toString());
                sessions.remove(session.id());
            }
        }
    }
}
