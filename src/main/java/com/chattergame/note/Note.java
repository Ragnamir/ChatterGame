package com.chattergame.note;

import com.chattergame.message.InboxSubject;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Заметка тетради (`REQUIREMENTS §15.1`).
 *
 * <p>Заметка принадлежит персонажу-цели, а не только автору: подсветка привязана к тому,
 * чьими глазами читают, и мастерская заметка на чужого персонажа подсвечивает его игроку
 * (`D-053`, `D-054`). Цель — та же пара «вид + идентификатор», что у адресата сообщения
 * (`D-086`): персонаж или участие мастера, потому что «заметка для мастера» не имеет
 * персонажа вовсе.
 *
 * <p>`matchingPending` — признак незавершённого досчёта (`D-087`). Пока он поднят, клиент
 * показывает «подсветка готовится» вместо пустого списка: молчаливый пустой результат
 * выглядит как сломанная фича.
 */
public record Note(
        UUID id,
        UUID gameId,
        UUID authorMembershipId,
        InboxSubject target,
        String body,
        boolean matchingPending,
        Instant createdAt,
        Instant updatedAt) {

    /** Лимит текста заметки (`D-098`). */
    public static final int BODY_MAX = 2000;

    /** Лимит числа ключевых слов на заметку (`D-098`). */
    public static final int KEYWORDS_MAX = 10;

    public static Note of(
            UUID id,
            UUID gameId,
            UUID authorMembershipId,
            InboxSubject target,
            String body,
            boolean matchingPending,
            Instant createdAt,
            Instant updatedAt) {
        return new Note(
                id, gameId, authorMembershipId, target, body, matchingPending, createdAt, updatedAt);
    }

    /** Заметка вместе с её ключами: клиенту они нужны, и второй запрос на это был бы лишним. */
    public record WithKeywords(Note note, List<NoteKeyword> keywords) {

        public boolean hasKeywords() {
            return !keywords.isEmpty();
        }
    }
}
