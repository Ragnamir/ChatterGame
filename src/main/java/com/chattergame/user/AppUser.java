package com.chattergame.user;

import java.time.Instant;
import java.util.UUID;

/**
 * Пользователь. Логин хранится в двух формах: отображаемой и нормализованной, по которой
 * идёт уникальность (`D-080`). Пароля здесь нет: он лежит в `auth_identity.secret_hash`,
 * потому что `auth_identity` — единственная таблица креденшалов (`D-078`).
 *
 * <p>`DELETED` — не удаление строки, а блокировка входа с анонимизацией PII (`D-095`):
 * персонажи и сообщения ссылаются на пользователя и обязаны остаться читаемыми.
 */
public record AppUser(
        UUID id,
        String login,
        String loginNormalized,
        String displayName,
        String email,
        Instant emailVerifiedAt,
        UUID avatarAssetId,
        String theme,
        String status,
        Instant createdAt,
        Instant updatedAt) {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DELETED = "DELETED";

    public boolean isActive() {
        return STATUS_ACTIVE.equals(status);
    }

    public boolean isDeleted() {
        return STATUS_DELETED.equals(status);
    }

    public boolean emailVerified() {
        return emailVerifiedAt != null;
    }
}
