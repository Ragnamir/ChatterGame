package com.chattergame.user;

import java.time.Instant;
import java.util.UUID;

/**
 * Одноразовый токен сброса пароля. В БД лежит хеш, а не токен: ссылка из письма
 * бесполезна для того, кто прочитал дамп. `usedAt` закрывает повторное использование,
 * при смене пароля все неиспользованные токены помечаются использованными (`D-079`).
 */
public record PasswordResetToken(
        UUID id,
        UUID userId,
        String tokenHash,
        Instant createdAt,
        Instant expiresAt,
        Instant usedAt) {

    public boolean usableAt(Instant now) {
        return usedAt == null && expiresAt.isAfter(now);
    }
}
