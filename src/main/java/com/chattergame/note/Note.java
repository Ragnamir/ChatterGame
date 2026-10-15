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

    /**
     * Заметка вместе с её ключами и подписью цели: клиенту нужны и то и другое, и второй
     * запрос на это был бы лишним.
     *
     * <p>`targetLabel` — маркер владельца (`D-053`): у игрока с несколькими персонажами
     * заметки приходят списком, и имя цели отличает одно замечание от другого. Имя берётся
     * живым и меняется вместе с карточкой (`D-127`): в отличие от снимка говорящего
     * (`D-055`), это подпись, а не исторический факт. Для цели-участия подписи нет: мастер
     * видит свою заметку в собственной панели, и чужого имени там взяться не может.
     */
    public record WithKeywords(Note note, List<NoteKeyword> keywords, String targetLabel) {

        public boolean hasKeywords() {
            return !keywords.isEmpty();
        }
    }
}
