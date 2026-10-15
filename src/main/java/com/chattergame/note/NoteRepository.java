package com.chattergame.note;

import com.chattergame.message.InboxSubject;
import com.chattergame.message.SubjectKind;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Доступ к `note`, `note_keyword` и `saved_message` (`REQUIREMENTS §15`).
 *
 * <p>`DELETE` здесь нет ни по одной из трёх таблиц: все они доменные, и физическое удаление
 * запрещено (`D-010`). Правка ключей выражается пометкой `superseded` (`007-e12.yaml`):
 * состав задаётся целиком, но старые строки остаются историей, а выборка берёт только
 * действующие. Отсутствие метода честнее метода, который бросает исключение.
 */
@Repository
public class NoteRepository {

    private static final String NOTE_COLUMNS =
            "id, game_id, author_membership_id, target_kind, target_id, body, matching_pending, "
                    + "created_at, updated_at";

    private static final String KEYWORD_COLUMNS =
            "id, note_id, raw, normalized, exact_match, superseded";

    private static final String SAVED_COLUMNS =
            "id, game_id, owner_membership_id, message_id, text_snapshot, speaker_snapshot, "
                    + "comment, note_id, created_at";

    private final JdbcTemplate jdbc;
    private final RowMapper<Note> noteMapper = this::mapNote;
    private final RowMapper<NoteKeyword> keywordMapper = this::mapKeyword;
    private final RowMapper<SavedMessage> savedMapper = this::mapSaved;

    public NoteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // --- Заметки ------------------------------------------------------------------------

    public void insert(Note note) {
        jdbc.update(
                "INSERT INTO note (id, game_id, author_membership_id, target_kind, target_id, "
                        + "body, matching_pending, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                note.id(),
                note.gameId(),
                note.authorMembershipId(),
                note.target().kind().name(),
                note.target().id(),
                note.body(),
                note.matchingPending(),
                Timestamp.from(note.createdAt()),
                Timestamp.from(note.updatedAt()));
    }

    /** Правка текста заметки: тело, признак досчёта и время правки одним запросом. */
    public void updateBody(UUID noteId, String body, boolean matchingPending, Instant updatedAt) {
        jdbc.update(
                "UPDATE note SET body = ?, matching_pending = ?, updated_at = ? WHERE id = ?",
                body,
                matchingPending,
                Timestamp.from(updatedAt),
                noteId);
    }

    public void setMatchingPending(UUID noteId, boolean pending) {
        jdbc.update("UPDATE note SET matching_pending = ? WHERE id = ?", pending, noteId);
    }

    public Optional<Note> findById(UUID noteId) {
        return jdbc.query("SELECT " + NOTE_COLUMNS + " FROM note WHERE id = ?", noteMapper, noteId)
                .stream()
                .findFirst();
    }

    public List<Note> findByGame(UUID gameId) {
        return jdbc.query(
                "SELECT " + NOTE_COLUMNS + " FROM note WHERE game_id = ? ORDER BY created_at",
                noteMapper,
                gameId);
    }

    /** Заметки с указанной целью: подсветка ищет их по субъекту читающего (`D-053`). */
    public List<Note> findByTarget(UUID gameId, InboxSubject target) {
        return jdbc.query(
                "SELECT " + NOTE_COLUMNS + " FROM note WHERE game_id = ? AND target_kind = ? "
                        + "AND target_id = ? ORDER BY created_at",
                noteMapper,
                gameId,
                target.kind().name(),
                target.id());
    }

    public List<Note> findByAuthor(UUID gameId, UUID authorMembershipId) {
        return jdbc.query(
                "SELECT " + NOTE_COLUMNS + " FROM note WHERE game_id = ? AND author_membership_id = ? "
                        + "ORDER BY created_at",
                noteMapper,
                gameId,
                authorMembershipId);
    }

    /** Заметки, у которых досчёт не завершён (`D-087`): задача добирает их следующим прогоном. */
    public List<Note> findPending() {
        return jdbc.query(
                "SELECT " + NOTE_COLUMNS + " FROM note WHERE matching_pending ORDER BY created_at",
                noteMapper);
    }

    // --- Ключевые слова -----------------------------------------------------------------

    public void insertKeyword(NoteKeyword keyword) {
        jdbc.update(
                "INSERT INTO note_keyword (id, note_id, raw, normalized, exact_match) "
                        + "VALUES (?, ?, ?, ?, ?)",
                keyword.id(),
                keyword.noteId(),
                keyword.raw(),
                keyword.normalized(),
                keyword.exactMatch());
    }

    /** Действующие ключи заметки. Помеченные `superseded` — история правки, не действуют. */
    public List<NoteKeyword> keywordsOf(UUID noteId) {
        return jdbc.query(
                "SELECT " + KEYWORD_COLUMNS + " FROM note_keyword WHERE note_id = ? "
                        + "AND NOT superseded ORDER BY raw",
                keywordMapper,
                noteId);
    }

    /** Действующие ключи списка заметок одним запросом: подсветка считает их на каждое сообщение. */
    public List<NoteKeyword> keywordsOfNotes(List<UUID> noteIds) {
        if (noteIds.isEmpty()) {
            return List.of();
        }
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < noteIds.size(); i++) {
            if (i > 0) {
                placeholders.append(", ");
            }
            placeholders.append('?');
        }
        return jdbc.query(
                "SELECT " + KEYWORD_COLUMNS + " FROM note_keyword WHERE note_id IN ("
                        + placeholders + ") AND NOT superseded ORDER BY raw",
                keywordMapper,
                new ArrayList<>(noteIds).toArray());
    }

    /**
     * Правка ключей заметки: старые помечаются `superseded`, новые вставляются. Выборка
     * после этого — ровно заданный состав, а история ключей не потеряна: физическое
     * удаление доменной строки запрещено (`D-010`).
     */
    public void replaceKeywords(UUID noteId, List<NoteKeyword> keywords) {
        jdbc.update(
                "UPDATE note_keyword SET superseded = true WHERE note_id = ? AND NOT superseded",
                noteId);
        for (NoteKeyword keyword : keywords) {
            insertKeyword(keyword);
        }
    }

    // --- Сохранённые сообщения ----------------------------------------------------------

    public void insertSaved(SavedMessage saved) {
        jdbc.update(
                "INSERT INTO saved_message (id, game_id, owner_membership_id, message_id, "
                        + "text_snapshot, speaker_snapshot, comment, note_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                saved.id(),
                saved.gameId(),
                saved.ownerMembershipId(),
                saved.messageId(),
                saved.textSnapshot(),
                saved.speakerSnapshot(),
                saved.comment(),
                saved.noteId(),
                Timestamp.from(saved.createdAt()));
    }

    public Optional<SavedMessage> findSaved(UUID savedId) {
        return jdbc.query(
                        "SELECT " + SAVED_COLUMNS + " FROM saved_message WHERE id = ?",
                        savedMapper,
                        savedId)
                .stream()
                .findFirst();
    }

    public Optional<SavedMessage> findSavedByOwnerAndMessage(UUID ownerMembershipId, UUID messageId) {
        return jdbc.query(
                        "SELECT " + SAVED_COLUMNS
                                + " FROM saved_message WHERE owner_membership_id = ? AND message_id = ?",
                        savedMapper,
                        ownerMembershipId,
                        messageId)
                .stream()
                .findFirst();
    }

    public List<SavedMessage> savedByOwner(UUID ownerMembershipId) {
        return jdbc.query(
                "SELECT " + SAVED_COLUMNS
                        + " FROM saved_message WHERE owner_membership_id = ? ORDER BY created_at DESC",
                savedMapper,
                ownerMembershipId);
    }

    public void updateSavedComment(UUID savedId, String comment) {
        jdbc.update("UPDATE saved_message SET comment = ? WHERE id = ?", comment, savedId);
    }

    /**
     * Снимок сохраняемого сообщения (`D-055`): тело, персонаж-говорящий и его имя.
     *
     * <p>Условие — `EXISTS` по `inbox_entry` хотя бы для одного из субъектов, которыми
     * смотрящий читает ленту (`D-030`): игрок читает персонажем, наблюдатель участием
     * (`D-063`), и мастер — тоже участием. Проверки только по участию мало: реплика
     * адресуется персонажу, и игрок не смог бы сохранить собственную реплику. Субъекты
     * проверяются все сразу, потому что набор известен вызывающему, а лишний запрос на
     * каждого персонажа игрока стоил бы N обращений к базе на одно сохранение.
     *
     * <p>Сохранить можно только то, что субъекту действительно выдали, иначе тетрадь
     * стала бы обходным путём к чужой переписке.
     */
    public Optional<Snapshot> snapshotFor(Collection<InboxSubject> readers, UUID messageId) {
        if (readers.isEmpty()) {
            return Optional.empty();
        }
        StringBuilder condition = new StringBuilder();
        List<Object> args = new ArrayList<>();
        args.add(messageId);
        for (InboxSubject reader : readers) {
            if (!condition.isEmpty()) {
                condition.append(" OR ");
            }
            condition.append("(i.subject_kind = ? AND i.subject_id = ?)");
            args.add(reader.kind().name());
            args.add(reader.id());
        }
        return jdbc.query(
                        "SELECT m.body AS body, m.character_id AS character_id, c.name AS name "
                                + "FROM message m "
                                + "LEFT JOIN character c ON c.id = m.character_id "
                                + "WHERE m.id = ? AND EXISTS (SELECT 1 FROM inbox_entry i "
                                + "WHERE i.message_id = m.id AND (" + condition + "))",
                        (rs, rowNum) -> new Snapshot(
                                rs.getString("body"),
                                rs.getString("name")),
                        args.toArray())
                .stream()
                .findFirst();
    }

    /** Снимок текста и имени говорящего: то, что защищает запись от молчаливой правки (`D-055`). */
    public record Snapshot(String text, String speaker) {
    }

    // --- Мапперы ------------------------------------------------------------------------

    private Note mapNote(ResultSet rs, int rowNum) throws SQLException {
        return Note.of(
                rs.getObject("id", UUID.class),
                rs.getObject("game_id", UUID.class),
                rs.getObject("author_membership_id", UUID.class),
                new InboxSubject(
                        SubjectKind.valueOf(rs.getString("target_kind")),
                        rs.getObject("target_id", UUID.class)),
                rs.getString("body"),
                rs.getBoolean("matching_pending"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private NoteKeyword mapKeyword(ResultSet rs, int rowNum) throws SQLException {
        return new NoteKeyword(
                rs.getObject("id", UUID.class),
                rs.getObject("note_id", UUID.class),
                rs.getString("raw"),
                rs.getString("normalized"),
                rs.getBoolean("exact_match"));
    }

    private SavedMessage mapSaved(ResultSet rs, int rowNum) throws SQLException {
        return new SavedMessage(
                rs.getObject("id", UUID.class),
                rs.getObject("game_id", UUID.class),
                rs.getObject("owner_membership_id", UUID.class),
                rs.getObject("message_id", UUID.class),
                rs.getString("text_snapshot"),
                rs.getString("speaker_snapshot"),
                rs.getString("comment"),
                rs.getObject("note_id", UUID.class),
                rs.getTimestamp("created_at").toInstant());
    }
}
