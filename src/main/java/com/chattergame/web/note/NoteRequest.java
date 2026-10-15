package com.chattergame.web.note;

import com.chattergame.message.SubjectKind;

import java.util.List;
import java.util.UUID;

/**
 * Заметка в запросе (`REQUIREMENTS §15.1`). Цель задаётся парой «вид + идентификатор» —
 * той же, что у адресата сообщения (`D-086`): персонаж или участие мастера. Ключи идут
 * с флагом точного совпадения (`D-051`), потому что режим имени — свойство ключа, а не
 * заметки: в одной заметке «Иван» может быть именем, а «козлоногий» — обычным словом.
 */
public record NoteRequest(
        SubjectKind targetKind,
        UUID targetId,
        String body,
        List<Keyword> keywords) {

    /** Ключ: слово и признак «сравнивать целиком» (для имён, `D-051`). */
    public record Keyword(String raw, boolean exactMatch) {
    }
}
