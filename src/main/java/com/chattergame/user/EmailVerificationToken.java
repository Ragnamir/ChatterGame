package com.chattergame.user;

import java.time.Instant;
import java.util.UUID;

/**
 * Одноразовый токен подтверждения адреса (`D-141`). В БД лежит хеш, адрес — часть токена:
 * письмо, ушедшее на прежний адрес, не подтверждает новый.
 */
public record EmailVerificationToken(
        UUID id,
        UUID userId,
        String tokenHash,
        String email,
        Instant createdAt,
        Instant expiresAt,
        Instant usedAt) {

    public boolean usableAt(Instant now) {
        return usedAt == null && expiresAt.isAfter(now);
    }
}
