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

/** Доступ к `password_reset_token`. Хранится хеш, поэтому поиск идёт по хешу, а не по токену. */
@Repository
public class PasswordResetTokenRepository {

    private static final String COLUMNS = "id, user_id, token_hash, created_at, expires_at, used_at";

    private static final RowMapper<PasswordResetToken> MAPPER = PasswordResetTokenRepository::map;

    private final JdbcTemplate jdbc;

    public PasswordResetTokenRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(PasswordResetToken token) {
        jdbc.update(
                "INSERT INTO password_reset_token (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, null)",
                token.id(),
                token.userId(),
                token.tokenHash(),
                AppUserRepository.timestamp(token.createdAt()),
                AppUserRepository.timestamp(token.expiresAt()));
    }

    public Optional<PasswordResetToken> findUsable(String tokenHash, Instant now) {
        List<PasswordResetToken> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM password_reset_token WHERE token_hash = ? "
                        + "AND used_at IS NULL AND expires_at > ?",
                MAPPER,
                tokenHash,
                AppUserRepository.timestamp(now));
        return rows.stream().findFirst();
    }

    public void markUsed(UUID id, Instant usedAt) {
        jdbc.update(
                "UPDATE password_reset_token SET used_at = ? WHERE id = ? AND used_at IS NULL",
                AppUserRepository.timestamp(usedAt),
                id);
    }

    /**
     * Инвалидация при смене или сбросе пароля: неиспользованные токены становятся
     * использованными. Строки не удаляются — иначе нельзя ответить, почему ссылка
     * перестала работать.
     */
    public int markAllUsedForUser(UUID userId, Instant usedAt) {
        return jdbc.update(
                "UPDATE password_reset_token SET used_at = ? WHERE user_id = ? AND used_at IS NULL",
                AppUserRepository.timestamp(usedAt),
                userId);
    }

    public void deleteAllForUser(UUID userId) {
        jdbc.update("DELETE FROM password_reset_token WHERE user_id = ?", userId);
    }

    private static PasswordResetToken map(ResultSet rs, int rowNum) throws SQLException {
        return new PasswordResetToken(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("token_hash"),
                AppUserRepository.instant(rs, "created_at"),
                AppUserRepository.instant(rs, "expires_at"),
                AppUserRepository.instant(rs, "used_at"));
    }
}
