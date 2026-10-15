package com.chattergame.note;

import java.time.Instant;
import java.util.UUID;

/**
 * Сохранённое сообщение (`REQUIREMENTS §15.2`, `D-055`).
 *
 * <p>Привязка — к участию, а не к персонажу: тетрадь остаётся при смене персонажа, потому
 * что это мета-инструмент игрока. Вместе со ссылкой хранится снимок текста и имени
 * говорящего: правка карточки мастером не должна менять чужие записи задним числом
 * (`D-055`), а отзыв сообщений невозможен (`D-008`), поэтому ссылка не «сломается»,
 * но её содержимое может измениться — снимок это и фиксирует.
 */
public record SavedMessage(
        UUID id,
        UUID gameId,
        UUID ownerMembershipId,
        UUID messageId,
        String textSnapshot,
        String speakerSnapshot,
        String comment,
        UUID noteId,
        Instant createdAt) {

    /** Лимит комментария: тот же предел, что у текста заметки (`D-098`). */
    public static final int COMMENT_MAX = 2000;
}
