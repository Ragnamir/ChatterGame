package com.chattergame.journal;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Событие журнала. `seq` — игровой порядок, выдаваемый под блокировкой счётчика игры;
 * `id` — внешний ключ (UUIDv7), и путать их нельзя: `seq` непрерывен в пределах игры,
 * `id` уникален глобально (`REQUIREMENTS §16`).
 *
 * <p>Payload не содержит закрытых историй и содержимого личных диалогов (`§16`), поэтому
 * приходит как готовый `Map` и пишется в `jsonb` без промежуточных DTO.
 */
public record GameEvent(
        UUID id,
        UUID gameId,
        long seq,
        GameEventType type,
        UUID chatId,
        UUID actorUserId,
        UUID characterId,
        boolean onBehalfOfMaster,
        Map<String, Object> payload,
        Instant createdAt) {
}
