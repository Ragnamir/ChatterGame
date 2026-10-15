package com.chattergame.note;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ключи заметок, сгруппированные по заметке (`§15.1`). Отдельный компонент, потому что
 * подсветку считают два пути — запись сообщения и досчёт (`D-050`, `D-087`), — и запрос
 * «дай ключи этих заметок» обязан быть один: два похожих запроса разошлись бы по фильтру
 * `superseded` при первой же правке заметки.
 */
@Component
public class KeywordIndex {

    private final NoteRepository notes;

    public KeywordIndex(NoteRepository notes) {
        this.notes = notes;
    }

    /** Действующие ключи заметок, разложенные по `note_id`. Заметка без ключей получает пустой список. */
    public Map<UUID, List<NoteKeyword>> forNotes(List<UUID> noteIds) {
        Map<UUID, List<NoteKeyword>> index = new LinkedHashMap<>();
        for (UUID noteId : noteIds) {
            index.put(noteId, List.of());
        }
        for (NoteKeyword keyword : notes.keywordsOfNotes(noteIds)) {
            index.computeIfPresent(keyword.noteId(), (id, list) -> {
                java.util.ArrayList<NoteKeyword> merged = new java.util.ArrayList<>(list);
                merged.add(keyword);
                return List.copyOf(merged);
            });
        }
        return index;
    }
}
