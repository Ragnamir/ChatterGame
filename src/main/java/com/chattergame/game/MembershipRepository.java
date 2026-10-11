package com.chattergame.game;

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
 * Доступ к `membership`. Участие уникально по `(game, user)`: у человека одна роль в одной
 * игре, а несколько персонажей — отдельная сущность (`REQUIREMENTS §6`, `D-002`).
 *
 * <p>Строка не удаляется даже при бане: `BANNED` — статус, а не отсутствие участия, иначе
 * журнал и персонажи остались бы без субъекта (`D-010`).
 */
@Repository
public class MembershipRepository {

    private static final String COLUMNS =
            "id, game_id, user_id, role, status, created_at, joined_at, left_at";

    private final JdbcTemplate jdbc;
    private final RowMapper<Membership> mapper = this::map;

    public MembershipRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Membership membership) {
        jdbc.update(
                "INSERT INTO membership (id, game_id, user_id, role, status, created_at, joined_at, "
                        + "left_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                membership.id(),
                membership.gameId(),
                membership.userId(),
                membership.role().name(),
                membership.status().name(),
                Timestamp.from(membership.createdAt()),
                membership.joinedAt() == null ? null : Timestamp.from(membership.joinedAt()),
                membership.leftAt() == null ? null : Timestamp.from(membership.leftAt()));
    }

    public void updateStatus(Membership membership) {
        jdbc.update(
                "UPDATE membership SET status = ?, joined_at = ?, left_at = ? WHERE id = ?",
                membership.status().name(),
                membership.joinedAt() == null ? null : Timestamp.from(membership.joinedAt()),
                membership.leftAt() == null ? null : Timestamp.from(membership.leftAt()),
                membership.id());
    }

    public Optional<Membership> find(UUID gameId, UUID userId) {
        List<Membership> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM membership WHERE game_id = ? AND user_id = ?",
                mapper,
                gameId,
                userId);
        return rows.stream().findFirst();
    }

    public List<Membership> findByGame(UUID gameId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM membership WHERE game_id = ? ORDER BY created_at",
                mapper,
                gameId);
    }

    private Membership map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp joinedAt = rs.getTimestamp("joined_at");
        Timestamp leftAt = rs.getTimestamp("left_at");
        return new Membership(
                rs.getObject("id", UUID.class),
                rs.getObject("game_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                MembershipRole.valueOf(rs.getString("role")),
                MembershipStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant(),
                joinedAt == null ? null : joinedAt.toInstant(),
                leftAt == null ? null : leftAt.toInstant());
    }
}
