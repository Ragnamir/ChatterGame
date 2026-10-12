package com.chattergame.chat;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Доступ к `transition`. Удаления нет: ребро архивируется с причиной (`D-009`, `D-010`).
 *
 * <p>Архивное ребро остаётся в таблице и в журнале — на него ссылаются события перемещений,
 * и пропавшая строка сделала бы транскрипт нечитаемым (`D-009`).
 */
@Repository
public class TransitionRepository {

    private static final String COLUMNS =
            "id, game_id, from_chat_id, to_chat_id, title, archived, archived_reason, "
                    + "archived_at, created_at";

    private final JdbcTemplate jdbc;
    private final RowMapper<Transition> mapper = this::map;

    public TransitionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Transition transition) {
        jdbc.update(
                "INSERT INTO transition (id, game_id, from_chat_id, to_chat_id, title, archived, "
                        + "archived_reason, archived_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                transition.id(),
                transition.gameId(),
                transition.fromChatId(),
                transition.toChatId(),
                transition.title(),
                transition.archived(),
                transition.archivedReason(),
                transition.archivedAt() == null ? null : Timestamp.from(transition.archivedAt()),
                Timestamp.from(transition.createdAt()));
    }

    public Optional<Transition> findById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM transition WHERE id = ?", mapper, id)
                .stream()
                .findFirst();
    }

    /** Все рёбра игры, включая архивные: фильтрация по архиву — дело читающего слоя. */
    public List<Transition> findByGame(UUID gameId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM transition WHERE game_id = ? ORDER BY created_at, id",
                mapper,
                gameId);
    }

    public void updateTitle(UUID id, String title) {
        jdbc.update("UPDATE transition SET title = ? WHERE id = ?", title, id);
    }

    public void markArchived(UUID id, String reason) {
        jdbc.update(
                "UPDATE transition SET archived = true, archived_reason = ?, archived_at = now() "
                        + "WHERE id = ?",
                reason,
                id);
    }

    private Transition map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp archivedAt = rs.getTimestamp("archived_at");
        return new Transition(
                rs.getObject("id", UUID.class),
                rs.getObject("game_id", UUID.class),
                rs.getObject("from_chat_id", UUID.class),
                rs.getObject("to_chat_id", UUID.class),
                rs.getString("title"),
                rs.getBoolean("archived"),
                rs.getString("archived_reason"),
                archivedAt == null ? null : archivedAt.toInstant(),
                rs.getTimestamp("created_at").toInstant());
    }
}
