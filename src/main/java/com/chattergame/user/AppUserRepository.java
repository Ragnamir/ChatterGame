package com.chattergame.user;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Доступ к `app_user`. Удаления здесь нет: аккаунт анонимизируется, а не исчезает (`D-095`). */
@Repository
public class AppUserRepository {

    private static final String COLUMNS =
            "id, login, login_normalized, display_name, email, email_verified_at, "
                    + "avatar_asset_id, theme, status, created_at, updated_at";

    private static final RowMapper<AppUser> MAPPER = AppUserRepository::map;

    private final JdbcTemplate jdbc;

    public AppUserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(AppUser user) {
        jdbc.update(
                "INSERT INTO app_user (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                user.id(),
                user.login(),
                user.loginNormalized(),
                user.displayName(),
                user.email(),
                timestamp(user.emailVerifiedAt()),
                user.avatarAssetId(),
                user.theme(),
                user.status(),
                timestamp(user.createdAt()),
                timestamp(user.updatedAt()));
    }

    public Optional<AppUser> findById(UUID id) {
        List<AppUser> rows =
                jdbc.query("SELECT " + COLUMNS + " FROM app_user WHERE id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }

    public Optional<AppUser> findByLoginNormalized(String loginNormalized) {
        List<AppUser> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM app_user WHERE login_normalized = ?",
                MAPPER,
                loginNormalized);
        return rows.stream().findFirst();
    }

    public Optional<AppUser> findByEmail(String normalizedEmail) {
        List<AppUser> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM app_user WHERE email = ?", MAPPER, normalizedEmail);
        return rows.stream().findFirst();
    }

    /**
     * Профиль. `email` передаётся нормализованным, `emailVerifiedAt` сбрасывается в null при
     * смене адреса: подтверждение относилось к прежнему адресу и на новый не переносится.
     */
    public void updateProfile(
            UUID id, String displayName, String email, Instant emailVerifiedAt, String theme) {
        jdbc.update(
                "UPDATE app_user SET display_name = ?, email = ?, email_verified_at = ?, theme = ?, "
                        + "updated_at = now() WHERE id = ?",
                displayName,
                email,
                timestamp(emailVerifiedAt),
                theme,
                id);
    }

    public void markEmailVerified(UUID id, Instant verifiedAt) {
        jdbc.update(
                "UPDATE app_user SET email_verified_at = ?, updated_at = now() WHERE id = ?",
                timestamp(verifiedAt),
                id);
    }

    /** Анонимизация PII удалённого аккаунта: логин, почта и отображаемое имя (`D-095`). */
    public void anonymize(
            UUID id, String login, String loginNormalized, String displayName, String status) {
        jdbc.update(
                "UPDATE app_user SET login = ?, login_normalized = ?, display_name = ?, email = null, "
                        + "email_verified_at = null, status = ?, updated_at = now() WHERE id = ?",
                login,
                loginNormalized,
                displayName,
                status,
                id);
    }

    private static AppUser map(ResultSet rs, int rowNum) throws SQLException {
        return new AppUser(
                rs.getObject("id", UUID.class),
                rs.getString("login"),
                rs.getString("login_normalized"),
                rs.getString("display_name"),
                rs.getString("email"),
                instant(rs, "email_verified_at"),
                rs.getObject("avatar_asset_id", UUID.class),
                rs.getString("theme"),
                rs.getString("status"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
