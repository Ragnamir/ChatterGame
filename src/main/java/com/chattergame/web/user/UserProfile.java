package com.chattergame.web.user;

import com.chattergame.user.AppUser;

import java.time.Instant;
import java.util.UUID;

/**
 * Профиль в ответе API. Пароля, хеша и ключей LLM здесь нет: клиенту они не нужны,
 * а поле, которого нет, невозможно случайно сериализовать (`D-093`).
 */
public record UserProfile(
        UUID id,
        String login,
        String displayName,
        String email,
        boolean emailVerified,
        UUID avatarAssetId,
        String theme,
        Instant createdAt) {

    public static UserProfile of(AppUser user) {
        return new UserProfile(
                user.id(),
                user.login(),
                user.displayName(),
                user.email(),
                user.emailVerified(),
                user.avatarAssetId(),
                user.theme(),
                user.createdAt());
    }
}
