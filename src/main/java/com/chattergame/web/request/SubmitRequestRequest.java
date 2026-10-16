package com.chattergame.web.request;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Подача заявки (`REQUIREMENTS §17`). `origin` и `type` — строки, а не перечисления: значение
 * приходит извне, и неизвестное обязано дать адресный машинный код, а не `400` от связывания
 * Jackson, по которому нельзя понять, что именно не понравилось (`D-043`).
 *
 * <p>`payload` — change set целиком, не разобранный на форме: заявка хранится такой, какой
 * пришла, и её разбор — дело машины применения, а не транспорта.
 */
public record SubmitRequestRequest(
        String origin,
        String type,
        UUID toCharacterId,
        Map<String, Object> payload,
        String idempotencyKey,
        Instant expiresAt) {
}
