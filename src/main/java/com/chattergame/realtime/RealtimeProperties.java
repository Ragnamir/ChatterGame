package com.chattergame.realtime;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Настройки реалтайма (`D-073`, `D-083`). Список `Origin` и таймауты — конфигурация, а не
 * константы: домен продакшна и адрес дев-сервера разные, а зашитое значение пришлось бы
 * менять вместе с кодом.
 */
@Component
@ConfigurationProperties(prefix = "chattergame.realtime")
public class RealtimeProperties {

    /** Домены, с которых разрешено открывать сокет. Пусто — не разрешено ниоткуда. */
    private List<String> allowedOrigins = List.of();

    /** Период сердцебиения: по нему мёртвый сокет отличается от молчащего. */
    private Duration heartbeatInterval = Duration.ofSeconds(20);

    /** Сколько молчания терпим, прежде чем закрыть сокет принудительно. */
    private Duration idleTimeout = Duration.ofSeconds(60);

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins == null ? List.of() : allowedOrigins;
    }

    public Duration getHeartbeatInterval() {
        return heartbeatInterval;
    }

    public void setHeartbeatInterval(Duration heartbeatInterval) {
        this.heartbeatInterval = heartbeatInterval;
    }

    public Duration getIdleTimeout() {
        return idleTimeout;
    }

    public void setIdleTimeout(Duration idleTimeout) {
        this.idleTimeout = idleTimeout;
    }
}
