package com.chattergame.message;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Доступ к `read_cursor` (`REQUIREMENTS §12`).
 *
 * <p>Курсор двигается только вперёд: `GREATEST` на месте обновления, а не сравнение
 * в сервисе. Два устройства одного игрока отмечают прочитанное независимо, и «отметил
 * раньше» не должно откатывать «отметил позже» — иначе лента начнёт повторяться.
 */
@Repository
public class ReadCursorRepository {

    private final JdbcTemplate jdbc;

    public ReadCursorRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ReadCursor> find(InboxSubject subject, UUID chatId) {
        return jdbc.query(
                        "SELECT subject_kind, subject_id, chat_id, last_seq, updated_at FROM read_cursor "
                                + "WHERE subject_kind = ? AND subject_id = ? AND chat_id = ?",
                        (rs, rowNum) -> new ReadCursor(
                                new InboxSubject(
                                        SubjectKind.valueOf(rs.getString("subject_kind")),
                                        rs.getObject("subject_id", UUID.class)),
                                rs.getObject("chat_id", UUID.class),
                                rs.getLong("last_seq"),
                                rs.getTimestamp("updated_at").toInstant()),
                        subject.kind().name(),
                        subject.id(),
                        chatId)
                .stream()
                .findFirst();
    }

    public long lastSeq(InboxSubject subject, UUID chatId) {
        return find(subject, chatId).map(ReadCursor::lastSeq).orElse(0L);
    }

    /** Отметка «прочитано до». Меньшее значение не применяется: курсор не откатывается. */
    public void advance(InboxSubject subject, UUID chatId, long seq) {
        jdbc.update(
                "INSERT INTO read_cursor (subject_kind, subject_id, chat_id, last_seq) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT (subject_kind, subject_id, chat_id) DO UPDATE "
                        + "SET last_seq = GREATEST(read_cursor.last_seq, EXCLUDED.last_seq), "
                        + "updated_at = now()",
                subject.kind().name(),
                subject.id(),
                chatId,
                seq);
    }

    /** Все курсоры субъекта: resume читает «сколько уже видели» по каждой подписке сразу. */
    public List<ReadCursor> findBySubject(InboxSubject subject) {
        return jdbc.query(
                "SELECT subject_kind, subject_id, chat_id, last_seq, updated_at FROM read_cursor "
                        + "WHERE subject_kind = ? AND subject_id = ?",
                (rs, rowNum) -> new ReadCursor(
                        new InboxSubject(
                                SubjectKind.valueOf(rs.getString("subject_kind")),
                                rs.getObject("subject_id", UUID.class)),
                        rs.getObject("chat_id", UUID.class),
                        rs.getLong("last_seq"),
                        rs.getTimestamp("updated_at").toInstant()),
                subject.kind().name(),
                subject.id());
    }
}
