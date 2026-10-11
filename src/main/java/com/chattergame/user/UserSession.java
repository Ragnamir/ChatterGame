package com.chattergame.user;

import java.time.Instant;
import java.util.UUID;

/**
 * Серверная сессия. В БД лежит только SHA-256 от токена из cookie: дамп базы не должен
 * давать возможность войти. `expiresAt` скользит при активности, `revokedAt` закрывает
 * сессию при выходе, смене и сбросе пароля (`D-096`).
 */
public record UserSession(
        UUID id,
        UUID userId,
        String tokenHash,
        String userAgent,
        Instant createdAt,
        Instant lastSeenAt,
        Instant expiresAt,
        Instant revokedAt) {

    public boolean active(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }
}
