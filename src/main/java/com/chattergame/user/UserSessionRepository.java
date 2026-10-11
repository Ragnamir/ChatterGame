package com.chattergame.user;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Доступ к `session`. Время передаётся параметром, а не берётся из `now()` в SQL: скольжение
 * срока и проверка «ещё жива» должны считаться по одним часам, иначе тест на истечение
 * зависит от момента выполнения запроса.
 */
@Repository
public class UserSessionRepository {

    private static final String COLUMNS =
            "id, user_id, token_hash, user_agent, created_at, last_seen_at, expires_at, revoked_at";

    private static final RowMapper<UserSession> MAPPER = UserSessionRepository::map;

    private final JdbcTemplate jdbc;

    public UserSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UserSession session) {
        jdbc.update(
                "INSERT INTO session (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, null)",
                session.id(),
                session.userId(),
                session.tokenHash(),
                session.userAgent(),
                AppUserRepository.timestamp(session.createdAt()),
                AppUserRepository.timestamp(session.lastSeenAt()),
                AppUserRepository.timestamp(session.expiresAt()));
    }

    public Optional<UserSession> findActive(String tokenHash, Instant now) {
        List<UserSession> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM session WHERE token_hash = ? AND revoked_at IS NULL "
                        + "AND expires_at > ?",
                MAPPER,
                tokenHash,
                AppUserRepository.timestamp(now));
        return rows.stream().findFirst();
    }

    public void touch(UUID id, Instant lastSeenAt, Instant expiresAt) {
        jdbc.update(
                "UPDATE session SET last_seen_at = ?, expires_at = ? WHERE id = ?",
                AppUserRepository.timestamp(lastSeenAt),
                AppUserRepository.timestamp(expiresAt),
                id);
    }

    public void revokeByTokenHash(String tokenHash, Instant revokedAt) {
        jdbc.update(
                "UPDATE session SET revoked_at = ? WHERE token_hash = ? AND revoked_at IS NULL",
                AppUserRepository.timestamp(revokedAt),
                tokenHash);
    }

    /** Смена и сброс пароля обрывают всё, включая текущее устройство (`D-096`). */
    public int revokeAllForUser(UUID userId, Instant revokedAt) {
        return jdbc.update(
                "UPDATE session SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL",
                AppUserRepository.timestamp(revokedAt),
                userId);
    }

    public int countActiveForUser(UUID userId, Instant now) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM session WHERE user_id = ? AND revoked_at IS NULL AND expires_at > ?",
                Integer.class,
                userId,
                AppUserRepository.timestamp(now));
        return count == null ? 0 : count;
    }

    /** Удаление аккаунта: сессии не нужны даже как история (`D-095`). */
    public void deleteAllForUser(UUID userId) {
        jdbc.update("DELETE FROM session WHERE user_id = ?", userId);
    }

    private static UserSession map(ResultSet rs, int rowNum) throws SQLException {
        return new UserSession(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("token_hash"),
                rs.getString("user_agent"),
                AppUserRepository.instant(rs, "created_at"),
                AppUserRepository.instant(rs, "last_seen_at"),
                AppUserRepository.instant(rs, "expires_at"),
                AppUserRepository.instant(rs, "revoked_at"));
    }
}
