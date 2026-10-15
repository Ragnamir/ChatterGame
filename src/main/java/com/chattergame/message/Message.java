package com.chattergame.message;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Сообщение (`REQUIREMENTS §11`). Тело и `payload` разные вещи: тело видит человек, `payload`
 * — метаданные события (номер хода, бюджет, служебные поля), и он же уходит в мета-канал
 * `D-035`, где тела сообщений нет.
 *
 * <p>`seq` — номер события журнала, породившего это сообщение, а не отдельный счётчик чата.
 * Журнал — единственный источник истины (`D-033`), `inbox_entry.event_seq` ссылается именно
 * на него, и resume читает по индексу `(subject_kind, subject_id, chat_id, seq)` (`D-083`).
 * Второй счётчик дал бы два порядка у одного сообщения и две правды о том, «после чего»
 * оно лежит.
 *
 * <p>Отзыв сообщения невозможен (`D-008`), поэтому здесь нет ни правки, ни удаления:
 * строка пишется один раз и живёт как есть. Исключение — `matchedNoteIds`: подсветка
 * ключей заметок досчитывается назад при создании и правке заметки (`D-087`), и это
 * запись, а не правка текста. Само тело сообщения не меняется никогда.
 */
public record Message(
        UUID id,
        UUID gameId,
        UUID chatId,
        long seq,
        MessageKind kind,
        UUID characterId,
        UUID authorMembershipId,
        UUID authorUserId,
        boolean onBehalfOfMaster,
        String body,
        Map<String, Object> payload,
        Instant createdAt,
        List<UUID> matchedNoteIds) {

    /** Максимум колонки `message.body` и лимит требования (`D-098`). */
    public static final int BODY_MAX = 4000;

    public Message(
            UUID id,
            UUID gameId,
            UUID chatId,
            long seq,
            MessageKind kind,
            UUID characterId,
            UUID authorMembershipId,
            UUID authorUserId,
            boolean onBehalfOfMaster,
            String body,
            Map<String, Object> payload,
            Instant createdAt) {
        this(
                id,
                gameId,
                chatId,
                seq,
                kind,
                characterId,
                authorMembershipId,
                authorUserId,
                onBehalfOfMaster,
                body,
                payload,
                createdAt,
                List.of());
    }
}
