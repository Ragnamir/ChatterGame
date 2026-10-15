package com.chattergame.web.note;

import java.time.Instant;
import java.util.UUID;

/**
 * Сохранённое сообщение в ответе (`REQUIREMENTS §15.2`, `D-055`).
 *
 * <p>`textSnapshot` и `speakerSnapshot` — снимки, а не живые ссылки: правка карточки
 * мастером не меняет запись задним числом. `noteId` необязателен: запись может ссылаться
 * на заметку тетради, но связь односторонняя — удаление заметки не трогает запись.
 */
public record SavedMessageView(
        UUID id,
        UUID gameId,
        UUID ownerMembershipId,
        UUID messageId,
        String textSnapshot,
        String speakerSnapshot,
        String comment,
        UUID noteId,
        Instant createdAt) {

    public static SavedMessageView of(com.chattergame.note.SavedMessage saved) {
        return new SavedMessageView(
                saved.id(),
                saved.gameId(),
                saved.ownerMembershipId(),
                saved.messageId(),
                saved.textSnapshot(),
                saved.speakerSnapshot(),
                saved.comment(),
                saved.noteId(),
                saved.createdAt());
    }
}
