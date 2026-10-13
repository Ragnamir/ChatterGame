package com.chattergame.restriction;

import com.chattergame.message.InboxSubject;

import java.time.Instant;
import java.util.UUID;

/**
 * Ограничение (`REQUIREMENTS §13`). Снятие — это `revokedAt`, а не удаление строки
 * (`D-010`): через месяц игры мастер должен видеть, что запрет был и почему.
 *
 * <p>`expiresAt` поддерживается, но не обязателен. Проверка срока делается на чтении
 * по текущему моменту, а не отдельной задачей: иначе «истёк» появлялся бы с задержкой.
 */
public record Restriction(
        UUID id,
        UUID gameId,
        InboxSubject subject,
        RestrictionKind kind,
        String reason,
        UUID createdBy,
        Instant createdAt,
        Instant expiresAt,
        Instant revokedAt) {

    /** Максимум колонки `restriction.reason`. */
    public static final int REASON_MAX = 500;

    public boolean activeAt(Instant moment) {
        return revokedAt == null && (expiresAt == null || expiresAt.isAfter(moment));
    }
}
