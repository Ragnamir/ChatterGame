package com.chattergame.user;

import java.time.Instant;
import java.util.UUID;

/**
 * Креденшал (`D-078`). Сейчас заполнен только `provider = LOCAL`, где
 * `providerUserId` — нормализованный логин, а `secretHash` — Argon2id-хеш пароля.
 *
 * <p>Google и VK добавляются строками этой же таблицы, без миграции `app_user`,
 * поэтому логика привязки не размазана по колонке `email` пользователя.
 */
public record AuthIdentity(
        UUID id,
        UUID userId,
        String provider,
        String providerUserId,
        String email,
        String secretHash,
        Instant verifiedAt,
        Instant createdAt) {

    public static final String PROVIDER_LOCAL = "LOCAL";
}
