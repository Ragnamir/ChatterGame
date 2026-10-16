package com.chattergame.request;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Заявка (`REQUIREMENTS §17`, `D-040`). Одна сущность на просьбу игрока, правку мастера и
 * предложение модели: различает их только {@link RequestOrigin}, а машина применения общая.
 *
 * <p>`payload` не разобран здесь: он объявлен как `jsonb` и читается при применении
 * ({@link ChangeSets}), потому что заявка может быть невалидной, и её надо уметь хранить
 * ровно такой, какой она пришла, — иначе разбор ошибки будет не по чему делать.
 */
public record GameRequest(
        UUID id,
        UUID gameId,
        RequestOrigin origin,
        RequestType type,
        UUID fromMembershipId,
        UUID toCharacterId,
        Map<String, Object> payload,
        RequestStatus status,
        String error,
        String idempotencyKey,
        Instant createdAt,
        Instant updatedAt,
        Instant expiresAt) {

    /** Просрочена ли заявка на момент `now`: срок жизни обязателен (`PLAN` E14, задача 6). */
    public boolean expiredAt(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now) && status.open();
    }
}
