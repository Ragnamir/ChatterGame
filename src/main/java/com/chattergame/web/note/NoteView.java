package com.chattergame.web.note;

import com.chattergame.note.Note;
import com.chattergame.note.NoteKeyword;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Заметка в ответе (`REQUIREMENTS §15.1`).
 *
 * <p>`targetKind`/`targetId` — цель заметки, по ней клиент ставит маркер владельца: у игрока
 * с несколькими персонажами заметки приходят списком, и объединение без маркера было бы
 * неотличимо от одной заметки (`D-053`).
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

    public static NoteView of(Note note, List<NoteKeyword> keywords) {
        return new NoteView(
                note.id(),
                note.gameId(),
                note.authorMembershipId(),
                note.target().kind().name(),
                note.target().id(),
                note.body(),
                note.matchingPending(),
                note.createdAt(),
                note.updatedAt(),
                keywords.stream().map(KeywordView::of).toList());
    }

    public static NoteView of(Note.WithKeywords note) {
        return of(note.note(), note.keywords());
    }
}
