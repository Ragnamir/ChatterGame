package com.chattergame.web.request;

import java.util.List;
import java.util.Map;

/**
 * Заявка глазами клиента (`REQUIREMENTS §17`). Отдаётся вместе с операциями, потому что
 * без них нечего одобрять: панель мастера показывает дифф по операциям (`D-041`), и
 * отдельный запрос за ними был бы вторым чтением той же строки.
 *
 * <p>`payload` операции приходит как есть, а не разобранным в типизированную форму: поле
 * `value` у правки персонажа может быть пустой строкой, а «не названо» и «названо пустым» —
 * разные вещи. Разбор в представлении стёр бы эту разницу.
 */
public record RequestView(
        String id,
        String gameId,
        String origin,
        String type,
        String fromMembershipId,
        String toCharacterId,
        String status,
        String error,
        String idempotencyKey,
        String createdAt,
        String expiresAt,
        Map<String, Object> payload,
        List<OperationView> operations) {

    public record OperationView(
            String id,
            int ordinal,
            String op,
            String status,
            String reason,
            Map<String, Object> payload,
            Map<String, Object> oldValue) {
    }
}
