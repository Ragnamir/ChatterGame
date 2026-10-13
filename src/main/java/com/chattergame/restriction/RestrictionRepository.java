package com.chattergame.restriction;

import com.chattergame.message.InboxSubject;
import com.chattergame.message.SubjectKind;
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
 * Доступ к `restriction`.
 *
 * <p>Активные ограничения читаются из базы на каждое событие, а не кэшируются в сокете
 * (`D-065`): сокет живёт часами, и запрет, навешенный минуту назад, обязан остановить
 * запись сразу. Отсюда узкая выборка по `(game, subject)` и индекс `ix_restriction_subject`
 * из baseline.
 *
 * <p>Удаления нет: снятие — это `revoked_at` (`D-010`, `D-064`). Появление здесь
 * `DELETE FROM restriction` обязано ломать гейт.
 */
@Repository
public class RestrictionRepository {

    private static final String COLUMNS =
            "id, game_id, subject_kind, subject_id, kind, reason, created_by, created_at, "
                    + "expires_at, revoked_at";

    private final JdbcTemplate jdbc;
    private final RowMapper<Restriction> mapper = this::map;

    public RestrictionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Restriction restriction) {
        jdbc.update(
                "INSERT INTO restriction (id, game_id, subject_kind, subject_id, kind, reason, "
                        + "created_by, expires_at, revoked_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                restriction.id(),
                restriction.gameId(),
                restriction.subject().kind().name(),
                restriction.subject().id(),
                restriction.kind().name(),
                restriction.reason(),
                restriction.createdBy(),
                restriction.expiresAt() == null ? null : Timestamp.from(restriction.expiresAt()),
                restriction.revokedAt() == null ? null : Timestamp.from(restriction.revokedAt()));
    }

    /**
     * Не снятые ограничения субъекта. Срок здесь не проверяется: «истекло ли» зависит от
     * текущего момента, и решать это должен вызывающий, а не выборка — иначе один и тот же
     * запрос в разных местах отвечал бы по-разному на границе срока.
     */
    public List<Restriction> findNotRevoked(UUID gameId, InboxSubject subject) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM restriction WHERE game_id = ? AND subject_kind = ? "
                        + "AND subject_id = ? AND revoked_at IS NULL ORDER BY created_at",
                mapper,
                gameId,
                subject.kind().name(),
                subject.id());
    }

    public Optional<Restriction> findById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM restriction WHERE id = ?", mapper, id)
                .stream()
                .findFirst();
    }

    /** Все запреты игры — для аудита мастера. */
    public List<Restriction> findByGame(UUID gameId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM restriction WHERE game_id = ? ORDER BY created_at",
                mapper,
                gameId);
    }

    /**
     * Не снятые запреты, касающиеся участия: и навешенные прямо на него (`NO_JOKE`), и
     * лежащие на его персонажах. Игроку показываются оба вида — иначе он узнавал бы о
     * немоте собственного персонажа только по отказу при попытке заговорить.
     */
    public List<Restriction> findForMembership(UUID gameId, UUID membershipId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM restriction WHERE game_id = ? AND revoked_at IS NULL "
                        + "AND ((subject_kind = ? AND subject_id = ?) "
                        + "     OR (subject_kind = ? AND subject_id IN "
                        + "         (SELECT id FROM character WHERE occupant_membership_id = ?))) "
                        + "ORDER BY created_at",
                mapper,
                gameId,
                SubjectKind.MEMBERSHIP.name(),
                membershipId,
                SubjectKind.CHARACTER.name(),
                membershipId);
    }

    /** Снятие — штатная операция мастера; строка остаётся, потому что она часть истории (`D-010`). */
    public void revoke(UUID id) {
        jdbc.update("UPDATE restriction SET revoked_at = now() WHERE id = ? AND revoked_at IS NULL", id);
    }

    private Restriction map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp expiresAt = rs.getTimestamp("expires_at");
        Timestamp revokedAt = rs.getTimestamp("revoked_at");
        return new Restriction(
                rs.getObject("id", UUID.class),
                rs.getObject("game_id", UUID.class),
                new InboxSubject(
                        SubjectKind.valueOf(rs.getString("subject_kind")),
                        rs.getObject("subject_id", UUID.class)),
                RestrictionKind.valueOf(rs.getString("kind")),
                rs.getString("reason"),
                rs.getObject("created_by", UUID.class),
                rs.getTimestamp("created_at").toInstant(),
                expiresAt == null ? null : expiresAt.toInstant(),
                revokedAt == null ? null : revokedAt.toInstant());
    }
}
