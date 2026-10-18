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
 * Доступ к `email_verification_token`. Поиск идёт по хешу, а не по токену: сам токен
 * существует только в письме.
 */
@Repository
public class EmailVerificationTokenRepository {

    private static final String COLUMNS =
            "id, user_id, token_hash, email, created_at, expires_at, used_at";

    private static final RowMapper<EmailVerificationToken> MAPPER =
            EmailVerificationTokenRepository::map;

    private final JdbcTemplate jdbc;

    public EmailVerificationTokenRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(EmailVerificationToken token) {
        jdbc.update(
                "INSERT INTO email_verification_token (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, null)",
                token.id(),
                token.userId(),
                token.tokenHash(),
                token.email(),
                AppUserRepository.timestamp(token.createdAt()),
                AppUserRepository.timestamp(token.expiresAt()));
    }

    public Optional<EmailVerificationToken> findUsable(String tokenHash, Instant now) {
        List<EmailVerificationToken> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM email_verification_token WHERE token_hash = ? "
                        + "AND used_at IS NULL AND expires_at > ?",
                MAPPER,
                tokenHash,
                AppUserRepository.timestamp(now));
        return rows.stream().findFirst();
    }

    public void markUsed(UUID id, Instant usedAt) {
        jdbc.update(
                "UPDATE email_verification_token SET used_at = ? WHERE id = ? AND used_at IS NULL",
                AppUserRepository.timestamp(usedAt),
                id);
    }

    /** Новое письмо гасит прежние ссылки: две живые ссылки на один адрес не нужны. */
    public int markAllUsedForUser(UUID userId, Instant usedAt) {
        return jdbc.update(
                "UPDATE email_verification_token SET used_at = ? WHERE user_id = ? AND used_at IS NULL",
                AppUserRepository.timestamp(usedAt),
                userId);
    }

    public void deleteAllForUser(UUID userId) {
        jdbc.update("DELETE FROM email_verification_token WHERE user_id = ?", userId);
    }

    private static EmailVerificationToken map(ResultSet rs, int rowNum) throws SQLException {
        return new EmailVerificationToken(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("token_hash"),
                rs.getString("email"),
                AppUserRepository.instant(rs, "created_at"),
                AppUserRepository.instant(rs, "expires_at"),
                AppUserRepository.instant(rs, "used_at"));
    }
}
