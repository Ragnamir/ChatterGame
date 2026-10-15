package com.chattergame.note;

import com.chattergame.message.InboxSubject;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Вычисление подсветки: какие заметки совпали с телом сообщения (`D-050`).
 *
 * <p>Живёт в пути записи и в досчёте (`D-087`) и только там. В read-пути совпадение не
 * считается никогда: это не оптимизация, а решение `D-050` — при морфологии русского
 * десятки заметок на тысячи сообщений дают непредсказуемое время отрисовки, а видимость
 * стабильна благодаря `D-007` и `D-008`, поэтому результат можно материализовать.
 *
 * <p>Набор заметок отбирается по субъектам-получателям сообщения: заметка целится в
 * субъекта (`§15.1`), а получатели — те же, кому материализуется видимость. Один и тот же
 * предикат «кому адресовано» даёт и строки ленты, и строки подсветки, иначе подсветка
 * появлялась бы у того, к кому сообщение не пришло.
 */
@Service
public class NoteMatchingService {

    private final NoteRepository notes;
    private final KeywordIndex keywords;

    public NoteMatchingService(NoteRepository notes, KeywordIndex keywords) {
        this.notes = notes;
        this.keywords = keywords;
    }

    /** Подсветка для одного тела. Пустой список — обычный случай, а не ошибка. */
    public List<UUID> matched(UUID gameId, Collection<InboxSubject> recipients, String body) {
        if (body == null || body.isBlank() || recipients.isEmpty()) {
            return List.of();
        }
        return matched(notesFor(gameId, recipients), body);
    }

    /** Заметки, нацеленные в любого из получателей: по одной строке на субъект, без дублей. */
    public Map<UUID, Note> notesFor(UUID gameId, Collection<InboxSubject> recipients) {
        Map<UUID, Note> found = new LinkedHashMap<>();
        for (InboxSubject subject : new LinkedHashSet<>(recipients)) {
            for (Note note : notes.findByTarget(gameId, subject)) {
                found.putIfAbsent(note.id(), note);
            }
        }
        return found;
    }

    /**
     * Совпадение по уже загруженным заметкам: ключи берутся одним запросом на весь набор,
     * а не по заметке. Заметка без ключей не подсвечивает ничего и в результат не попадает.
     */
    public List<UUID> matched(Map<UUID, Note> candidates, String body) {
        if (body == null || body.isBlank() || candidates.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<NoteKeyword>> index = keywords.forNotes(List.copyOf(candidates.keySet()));
        LinkedHashSet<UUID> hits = new LinkedHashSet<>();
        for (Map.Entry<UUID, List<NoteKeyword>> entry : index.entrySet()) {
            for (NoteKeyword keyword : entry.getValue()) {
                if (KeywordMatcher.matches(keyword.raw(), keyword.exactMatch(), body)) {
                    hits.add(entry.getKey());
                    break;
                }
            }
        }
        return List.copyOf(hits);
    }
}
