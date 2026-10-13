package com.chattergame.message;

import java.time.Instant;
import java.util.UUID;

/**
 * Курсор прочтения субъекта в чате (`REQUIREMENTS §12`). Хранит последний прочитанный
 * `seq`, а не список прочитанных строк: список рос бы вместе с игрой, а курсор — нет.
 *
 * <p>Курсор двигается только вперёд. Вход в локацию ставит его на последнее сообщение
 * (`D-032`), и это прыжок вперёд, а не назад: персонаж, вернувшийся в комнату, не должен
 * получить всё, что там писалось в его отсутствие.
 */
public record ReadCursor(
        InboxSubject subject,
        UUID chatId,
        long lastSeq,
        Instant updatedAt) {
}
