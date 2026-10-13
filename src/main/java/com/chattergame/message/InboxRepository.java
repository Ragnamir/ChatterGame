package com.chattergame.message;

import com.chattergame.util.Uuids;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Доступ к `inbox_entry` — материализованной видимости (`D-030`).
 *
 * <p>Вставка идемпотентна: уникальный индекс `(subject_kind, subject_id, chat_id, event_seq)`
 * плюс `ON CONFLICT DO NOTHING` делают повторную материализацию безопасной. Это нужно
 * backfill-у наблюдателя и досчёту совпадений заметок (`D-086`, `D-087`) — обе операции
 * пишут, а не фильтруют на чтении.
 *
 * <p>Счётчики непрочитанного считаются здесь же и выводятся из тех же строк (`D-036`):
 * отдельной таблицы счётчиков нет, иначе она разошлась бы с лентой при первой же задержке.
 */
@Repository
public class InboxRepository {

    private final JdbcTemplate jdbc;

    public InboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(InboxEntry entry) {
        jdbc.update(
                "INSERT INTO inbox_entry (id, subject_kind, subject_id, chat_id, event_seq, message_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (subject_kind, subject_id, chat_id, event_seq) DO NOTHING",
                entry.id(),
                entry.subject().kind().name(),
                entry.subject().id(),
                entry.chatId(),
                entry.eventSeq(),
                entry.messageId());
    }

    /** Есть ли уже строка у этого субъекта: нужно тестам и проверкам идемпотентности. */
    public boolean exists(InboxSubject subject, UUID chatId, long eventSeq) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM inbox_entry WHERE subject_kind = ? AND subject_id = ? "
                        + "AND chat_id = ? AND event_seq = ?",
                Long.class,
                subject.kind().name(),
                subject.id(),
                chatId,
                eventSeq);
        return count != null && count > 0;
    }

    public long countFor(InboxSubject subject) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM inbox_entry WHERE subject_kind = ? AND subject_id = ?",
                Long.class,
                subject.kind().name(),
                subject.id());
        return count == null ? 0L : count;
    }

    /** Непрочитанное в одном чате: счётчик мета-события сокета выводится из этих же строк (`D-036`). */
    public long unreadIn(InboxSubject subject, UUID chatId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM inbox_entry i "
                        + "LEFT JOIN read_cursor r ON r.subject_kind = i.subject_kind "
                        + "AND r.subject_id = i.subject_id AND r.chat_id = i.chat_id "
                        + "WHERE i.subject_kind = ? AND i.subject_id = ? AND i.chat_id = ? "
                        + "AND i.event_seq > coalesce(r.last_seq, 0)",
                Long.class,
                subject.kind().name(),
                subject.id(),
                chatId);
        return count == null ? 0L : count;
    }

    /** Непрочитанное по чатам: то, что содержит лента (`D-036`), а не отдельный счётчик. */
    public List<Unread> unread(InboxSubject subject) {
        return jdbc.query(
                "SELECT i.chat_id, count(*) AS unread FROM inbox_entry i "
                        + "LEFT JOIN read_cursor r ON r.subject_kind = i.subject_kind "
                        + "AND r.subject_id = i.subject_id AND r.chat_id = i.chat_id "
                        + "WHERE i.subject_kind = ? AND i.subject_id = ? "
                        + "AND i.event_seq > coalesce(r.last_seq, 0) GROUP BY i.chat_id",
                (rs, rowNum) -> new Unread(
                        rs.getObject("chat_id", UUID.class), rs.getLong("unread")),
                subject.kind().name(),
                subject.id());
    }

    /** Безадресная проекция журнала: кому событие чата было выдано (`SYSTEM`, `D-031`). */
    public List<InboxSubject> subjectsFor(UUID chatId, long eventSeq) {
        return jdbc.query(
                "SELECT subject_kind, subject_id FROM inbox_entry WHERE chat_id = ? AND event_seq = ?",
                (rs, rowNum) -> new InboxSubject(
                        SubjectKind.valueOf(rs.getString("subject_kind")),
                        rs.getObject("subject_id", UUID.class)),
                chatId,
                eventSeq);
    }

    static UUID newId() {
        return Uuids.v7();
    }

    /** Непрочитанное по одному чату: бейдж — это проекция строк `inbox_entry` (`D-036`). */
    public record Unread(UUID chatId, long count) {
    }
}
