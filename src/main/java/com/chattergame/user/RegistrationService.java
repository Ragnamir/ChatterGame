package com.chattergame.user;

import com.chattergame.util.ApiException;
import com.chattergame.util.Uuids;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Открытая регистрация (`D-091`). Логин и пароль, пароль хешируется Argon2id, почта
 * необязательна и не является логином.
 *
 * <p>Проверка занятости логина перед вставкой не заменяет уникальный индекс: две
 * одновременные регистрации проходят проверку обе, и решает уже база. Оба пути обязаны
 * давать одинаковый ответ, иначе повторная регистрация то 409, то 500.
 */
@Service
public class RegistrationService {

    private final AppUserRepository users;
    private final AuthIdentityRepository identities;
    private final PasswordHasher hasher;
    private final Clock clock;
    private final boolean autoVerifyEmail;

    public RegistrationService(
            AppUserRepository users,
            AuthIdentityRepository identities,
            PasswordHasher hasher,
            Clock clock,
            @Value("${chattergame.mail.auto-verify-email:false}") boolean autoVerifyEmail) {
        this.users = users;
        this.identities = identities;
        this.hasher = hasher;
        this.clock = clock;
        this.autoVerifyEmail = autoVerifyEmail;
    }

    @Transactional
    public AppUser register(String rawLogin, String rawPassword, String rawDisplayName, String rawEmail) {
        String login = Logins.display(rawLogin == null ? "" : rawLogin);
        String loginNormalized = Logins.normalize(login);
        validateLogin(login);
        validatePassword(rawPassword);

        String displayName = displayNameOrLogin(rawDisplayName, login);
        String email = normalizeEmail(rawEmail);
        if (email != null && !Emails.looksValid(email)) {
            throw ApiException.badRequest("invalid_email", "Некорректный адрес почты");
        }

        if (users.findByLoginNormalized(loginNormalized).isPresent()) {
            throw loginTaken();
        }
        if (email != null && users.findByEmail(email).isPresent()) {
            throw emailTaken();
        }

        Instant now = clock.instant();
        Instant verifiedAt = email != null && autoVerifyEmail ? now : null;
        UUID id = Uuids.v7();
        try {
            users.insert(new AppUser(
                    id,
                    login,
                    loginNormalized,
                    displayName,
                    email,
                    verifiedAt,
                    null,
                    "system",
                    AppUser.STATUS_ACTIVE,
                    now,
                    now));
            identities.insert(new AuthIdentity(
                    Uuids.v7(),
                    id,
                    AuthIdentity.PROVIDER_LOCAL,
                    loginNormalized,
                    email,
                    hasher.hash(rawPassword),
                    verifiedAt,
                    now));
        } catch (DuplicateKeyException e) {
            throw emailConflict(e) ? emailTaken() : loginTaken();
        }
        return users.findById(id).orElseThrow();
    }

    private static void validateLogin(String login) {
        if (login.isEmpty()) {
            throw ApiException.badRequest("login_required", "Логин обязателен");
        }
        if (Logins.hasControlCharacters(login)) {
            throw ApiException.badRequest("invalid_login", "Логин содержит недопустимые символы");
        }
        if (!Logins.lengthAllowed(login)) {
            throw ApiException.badRequest(
                    "invalid_login",
                    "Логин должен быть от " + Logins.MIN_LENGTH + " до " + Logins.MAX_LENGTH + " символов");
        }
    }

    private static void validatePassword(String rawPassword) {
        if (rawPassword == null || !PasswordHasher.lengthAllowed(rawPassword)) {
            throw ApiException.badRequest(
                    "invalid_password",
                    "Пароль должен быть от " + PasswordHasher.MIN_LENGTH + " до "
                            + PasswordHasher.MAX_LENGTH + " символов");
        }
    }

    private static String displayNameOrLogin(String rawDisplayName, String login) {
        String displayName = rawDisplayName == null ? "" : rawDisplayName.strip();
        return displayName.isEmpty() ? login : displayName;
    }

    private static String normalizeEmail(String rawEmail) {
        if (rawEmail == null || rawEmail.isBlank()) {
            return null;
        }
        return Emails.normalize(rawEmail);
    }

    private static boolean emailConflict(DuplicateKeyException e) {
        String message = e.getMostSpecificCause().getMessage();
        return message != null && message.contains("ux_app_user_email");
    }

    static ApiException loginTaken() {
        return ApiException.conflict("login_taken", "Логин уже занят");
    }

    static ApiException emailTaken() {
        return ApiException.conflict("email_taken", "Почта уже используется");
    }
}
