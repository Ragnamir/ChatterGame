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
            "id, game_id, user_id, role, status, created_at, joined_at, left_at, reserved_character_id, "
                    + "roleplay_wishes";

    private final JdbcTemplate jdbc;
    private final RowMapper<Membership> mapper = this::map;

    public MembershipRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Membership membership) {
        jdbc.update(
                "INSERT INTO membership (id, game_id, user_id, role, status, created_at, joined_at, "
                        + "left_at, reserved_character_id, roleplay_wishes) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                membership.id(),
                membership.gameId(),
                membership.userId(),
                membership.role().name(),
                membership.status().name(),
                Timestamp.from(membership.createdAt()),
                membership.joinedAt() == null ? null : Timestamp.from(membership.joinedAt()),
                membership.leftAt() == null ? null : Timestamp.from(membership.leftAt()),
                membership.reservedCharacterId(),
                membership.roleplayWishes());
    }

    public void updateStatus(Membership membership) {
        jdbc.update(
                "UPDATE membership SET status = ?, joined_at = ?, left_at = ?, role = ?, "
                        + "reserved_character_id = ?, roleplay_wishes = ? WHERE id = ?",
                membership.status().name(),
                membership.joinedAt() == null ? null : Timestamp.from(membership.joinedAt()),
                membership.leftAt() == null ? null : Timestamp.from(membership.leftAt()),
                membership.role().name(),
                membership.reservedCharacterId(),
                membership.roleplayWishes(),
                membership.id());
    }

    /**
     * «Пожелания по отыгрышу» (REQUIREMENTS §17): свободный текст игрока, который мастер
     * читает при распределении ролей. Отдельный `UPDATE`, а не перезапись строки целиком:
     * пожелание меняет сам игрок, и статус участия при этом не трогается — иначе запись
     * пожелания затирала бы решение мастера о бане или выходе, принятое между чтением и записью.
     */
    public void updateRoleplayWishes(UUID membershipId, String wishes) {
        jdbc.update(
                "UPDATE membership SET roleplay_wishes = ? WHERE id = ?", wishes, membershipId);
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

    /**
     * Участие, забронировавшее персонажа приглашением (`D-130`). Читается при захвате слота:
     * бронь не занимает персонажа, но и не отдаёт его чужому.
     */
    public Optional<Membership> findReserving(UUID characterId) {
        return jdbc.query(
                        "SELECT " + COLUMNS + " FROM membership "
                                + "WHERE reserved_character_id = ? AND status = 'INVITED'",
                        mapper,
                        characterId)
                .stream()
                .findFirst();
    }

    public Optional<Membership> findById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM membership WHERE id = ?", mapper, id)
                .stream()
                .findFirst();
    }

    /**
     * Участия с незавершённым досчётом видимости (`D-086`). Список читается при старте
     * прогона, а не хранится в памяти: признак лежит в БД именно затем, чтобы упавший узел
     * оставил след.
     */
    public List<UUID> findPendingBackfill() {
        return jdbc.queryForList(
                "SELECT id FROM membership WHERE backfill_pending = true AND status = 'JOINED' "
                        + "ORDER BY created_at",
                UUID.class);
    }

    public void setBackfillPending(UUID membershipId, boolean pending) {
        jdbc.update(
                "UPDATE membership SET backfill_pending = ? WHERE id = ?", pending, membershipId);
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
                leftAt == null ? null : leftAt.toInstant(),
                rs.getObject("reserved_character_id", UUID.class),
                rs.getString("roleplay_wishes"));
    }
}
