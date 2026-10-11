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

/** Доступ к `auth_identity`. Провайдер локальный, но таблица уже рассчитана на OAuth (`D-078`). */
@Repository
public class AuthIdentityRepository {

    private static final String COLUMNS =
            "id, user_id, provider, provider_user_id, email, secret_hash, verified_at, created_at";

    private static final RowMapper<AuthIdentity> MAPPER = AuthIdentityRepository::map;

    private final JdbcTemplate jdbc;

    public AuthIdentityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(AuthIdentity identity) {
        jdbc.update(
                "INSERT INTO auth_identity (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                identity.id(),
                identity.userId(),
                identity.provider(),
                identity.providerUserId(),
                identity.email(),
                identity.secretHash(),
                AppUserRepository.timestamp(identity.verifiedAt()),
                AppUserRepository.timestamp(identity.createdAt()));
    }

    public Optional<AuthIdentity> findLocalByUserId(UUID userId) {
        List<AuthIdentity> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM auth_identity WHERE user_id = ? AND provider = ?",
                MAPPER,
                userId,
                AuthIdentity.PROVIDER_LOCAL);
        return rows.stream().findFirst();
    }

    public void updateSecretHash(UUID userId, String secretHash) {
        jdbc.update(
                "UPDATE auth_identity SET secret_hash = ? WHERE user_id = ? AND provider = ?",
                secretHash,
                userId,
                AuthIdentity.PROVIDER_LOCAL);
    }

    /** Подтверждение относится к конкретному адресу, поэтому хранится рядом с ним. */
    public void updateEmail(UUID userId, String email, Instant verifiedAt) {
        jdbc.update(
                "UPDATE auth_identity SET email = ?, verified_at = ? WHERE user_id = ? AND provider = ?",
                email,
                AppUserRepository.timestamp(verifiedAt),
                userId,
                AuthIdentity.PROVIDER_LOCAL);
    }

    /**
     * Анонимизация (`D-095`): адрес и хеш пароля стираются, `provider_user_id` перестаёт
     * совпадать с логином. Вход после этого невозможен даже при угаданном пароле.
     */
    public void anonymize(UUID userId, String providerUserId) {
        jdbc.update(
                "UPDATE auth_identity SET email = null, secret_hash = null, verified_at = null, "
                        + "provider_user_id = ? WHERE user_id = ?",
                providerUserId,
                userId);
    }

    private static AuthIdentity map(ResultSet rs, int rowNum) throws SQLException {
        return new AuthIdentity(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("provider"),
                rs.getString("provider_user_id"),
                rs.getString("email"),
                rs.getString("secret_hash"),
                AppUserRepository.instant(rs, "verified_at"),
                AppUserRepository.instant(rs, "created_at"));
    }
}
