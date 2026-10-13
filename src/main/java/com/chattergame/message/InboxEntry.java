package com.chattergame.message;

import java.time.Instant;
import java.util.UUID;

/**
 * Строка входящего субъекта: «этому персонажу (или участию) это событие этого чата
 * адресовано» (`D-030`, `D-086`).
 *
 * <p>`messageId` пуст только у проекции журнала: у системного события тела в `message` нет,
 * и получатель определяется по `eventSeq`. Поле необязательное именно ради этого случая —
 * иначе `SYSTEM` пришлось бы писать в `message` пустой строкой.
 */
public record InboxEntry(
        UUID id,
        InboxSubject subject,
        UUID chatId,
        long eventSeq,
        UUID messageId,
        Instant createdAt) {
}
