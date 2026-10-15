package com.chattergame.web.note;

import com.chattergame.note.Note;
import com.chattergame.note.NoteKeyword;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Заметка в ответе (`REQUIREMENTS §15.1`).
 *
 * <p>`targetKind`/`targetId`/`targetLabel` — цель заметки и её подпись: маркер владельца
 * у игрока с несколькими персонажами (`D-053`). Подпись отдаётся готовой (`D-127`), чтобы
 * панель не ходила вторым запросом за именами персонажей. Имя приходит живым: правка карточки
 * мастером сдвигает подпись, и это осознанно отличается от снимка говорящего (`D-055`).
 *
 * <p>`matchingPending` — признак незавершённого досчёта (`D-087`). Пока он поднят, клиент
 * показывает «подсветка готовится», а не пустой список: молчаливое отсутствие подсветки
 * выглядит как сломанная фича.
 */
public record NoteView(
        UUID id,
        UUID gameId,
        UUID authorMembershipId,
        String targetKind,
        UUID targetId,
        String targetLabel,
        String body,
        boolean matchingPending,
        Instant createdAt,
        Instant updatedAt,
        List<KeywordView> keywords) {

    /** Ключ в ответе: `normalized` не отдаётся — это внутренняя форма сравнения (`D-080`). */
    public record KeywordView(UUID id, String raw, boolean exactMatch) {

        static KeywordView of(NoteKeyword keyword) {
            return new KeywordView(keyword.id(), keyword.raw(), keyword.exactMatch());
        }
    }

    public static NoteView of(Note note, List<NoteKeyword> keywords, String targetLabel) {
        return new NoteView(
                note.id(),
                note.gameId(),
                note.authorMembershipId(),
                note.target().kind().name(),
                note.target().id(),
                targetLabel,
                note.body(),
                note.matchingPending(),
                note.createdAt(),
                note.updatedAt(),
                keywords.stream().map(KeywordView::of).toList());
    }

    public static NoteView of(Note.WithKeywords note) {
        return of(note.note(), note.keywords(), note.targetLabel());
    }
}
