package com.chattergame.note;

import com.chattergame.message.MessageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Досчёт подсветки назад (`D-087`).
 *
 * <p>Без досчёта игрок создаёт заметку о слове, которое встречалось три недели назад, и не
 * видит ни одной подсветки: фича выглядит сломанной в первый же день. Досчёт дёшев именно
 * благодаря `D-030`: область — строки `inbox_entry` одного целевого субъекта, а не все
 * сообщения игры, то есть десятки тысяч строк вместо миллионов.
 *
 * <p>Работа идёт отдельной транзакцией от создания заметки: держать её в транзакции запроса
 * значило бы заставить игрока ждать весь пересчёт, а `matching_pending` у заметки существует
 * как раз чтобы это ожидание было видимым (`D-087`).
 */
@Service
public class NoteBackfillService {

    private final NoteRepository notes;
    private final NoteMatchingService matching;
    private final KeywordIndex keywords;
    private final MessageRepository messages;

    public NoteBackfillService(
            NoteRepository notes,
            NoteMatchingService matching,
            KeywordIndex keywords,
            MessageRepository messages) {
        this.notes = notes;
        this.matching = matching;
        this.keywords = keywords;
        this.messages = messages;
    }

    /**
     * Все заметки с незавершённым досчётом, по всем играм. Возвращает счётчики, чтобы
     * проверка «область ограничена субъектом» опиралась на число обработанных строк, а не
     * на слова: обработка — это проход по `inbox_entry` целевого субъекта.
     */
    @Transactional
    public Report backfillAll() {
        List<Note> pending = notes.findPending();
        int rows = 0;
        for (Note note : pending) {
            rows += backfill(note);
            notes.setMatchingPending(note.id(), false);
        }
        return new Report(pending.size(), rows);
    }

    /** Досчёт одной заметки: строки материала целевого субъекта, у которых есть тело. */
    @Transactional
    public Report backfillNote(Note note) {
        int rows = backfill(note);
        notes.setMatchingPending(note.id(), false);
        return new Report(1, rows);
    }

    private int backfill(Note note) {
        List<NoteKeyword> noteKeywords = keywords.forNotes(List.of(note.id())).get(note.id());
        if (noteKeywords == null) {
            noteKeywords = List.of();
        }
        int processed = 0;
        for (MessageRepository.NoteMatchRow row : messages.feedForMatching(note.target())) {
            processed++;
            boolean hit = !noteKeywords.isEmpty()
                    && row.body() != null
                    && !row.body().isBlank()
                    && noteKeywords.stream()
                            .anyMatch(keyword -> KeywordMatcher.matches(
                                    keyword.raw(), keyword.exactMatch(), row.body()));
            messages.setMatchedNote(
                    row.messageId(), note.id(), hit, row.matchedNoteIds().contains(note.id()));
        }
        return processed;
    }

    /** Счётчики прогона: сколько заметок досчитано и сколько строк материала просмотрено. */
    public record Report(int notes, int rows) {
    }
}
