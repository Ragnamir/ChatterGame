package com.chattergame.note;

import java.util.UUID;

/**
 * Ключевое слово заметки (`REQUIREMENTS §15.1`).
 *
 * <p>Хранится три формы: `raw` — как набрал человек, `normalized` — посчитанная сервером
 * форма для сравнения (`D-080`), `exactMatch` — режим имени (`D-051`). Держать только
 * `raw` значило бы считать стеммер на каждом сообщении; только `normalized` — потерять
 * то, что человек написал, и показать ему в настройках заметки не своё слово.
 */
public record NoteKeyword(
        UUID id,
        UUID noteId,
        String raw,
        String normalized,
        boolean exactMatch) {

    public static NoteKeyword of(UUID id, UUID noteId, String raw, boolean exactMatch) {
        return new NoteKeyword(
                id, noteId, raw.strip(), KeywordMatcher.storedForm(raw, exactMatch), exactMatch);
    }
}
