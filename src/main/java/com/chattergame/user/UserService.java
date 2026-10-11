package com.chattergame.user;

import com.chattergame.util.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Профиль: чтение, отображаемое имя, почта, тема. Смена пароля живёт в {@link PasswordService},
 * потому что делит инварианты со сбросом по ссылке.
 */
@Service
public class UserService {

    private static final Set<String> THEMES = Set.of("system", "light", "dark");
    private static final int DISPLAY_NAME_MAX = 128;

    private final AppUserRepository users;
    private final AuthIdentityRepository identities;
    private final PasswordService passwords;
    private final Clock clock;
    private final boolean autoVerifyEmail;

    public UserService(
            AppUserRepository users,
            AuthIdentityRepository identities,
            PasswordService passwords,
            Clock clock,
            @Value("${chattergame.mail.auto-verify-email:false}") boolean autoVerifyEmail) {
        this.users = users;
        this.identities = identities;
        this.passwords = passwords;
        this.clock = clock;
        this.autoVerifyEmail = autoVerifyEmail;
    }

    public AppUser require(UUID userId) {
        return users.findById(userId)
                .filter(AppUser::isActive)
                .orElseThrow(UserService::notFound);
    }

    public int changePassword(UUID userId, String currentPassword, String newPassword) {
        return passwords.changePassword(userId, currentPassword, newPassword);
    }

    /**
     * Обновление профиля. `null` означает «не трогать», пустая строка у почты — «убрать»:
     * иначе от смены имени нельзя было бы отказаться, не переслав весь объект.
     *
     * <p>При смене адреса подтверждение сбрасывается: прежнее относилось к прежнему адресу.
     */
    @Transactional
    public AppUser updateProfile(UUID userId, String displayName, String email, String theme) {
        AppUser current = require(userId);

        String newDisplayName = current.displayName();
        if (displayName != null) {
            newDisplayName = displayName.strip();
            if (newDisplayName.isEmpty()) {
                throw ApiException.badRequest("invalid_display_name", "Отображаемое имя не может быть пустым");
            }
            if (newDisplayName.length() > DISPLAY_NAME_MAX) {
                throw ApiException.badRequest(
                        "invalid_display_name",
                        "Отображаемое имя не длиннее " + DISPLAY_NAME_MAX + " символов");
            }
        }

        String newEmail = current.email();
        Instant verifiedAt = current.emailVerifiedAt();
        if (email != null) {
            String candidate = email.strip().isEmpty() ? null : Emails.normalize(email);
            if (candidate != null && !Emails.looksValid(candidate)) {
                throw ApiException.badRequest("invalid_email", "Некорректный адрес почты");
            }
            if (!Objects.equals(candidate, current.email())) {
                if (candidate != null) {
                    users.findByEmail(candidate)
                            .filter(other -> !other.id().equals(userId))
                            .ifPresent(other -> {
                                throw RegistrationService.emailTaken();
                            });
                }
                newEmail = candidate;
                verifiedAt = candidate != null && autoVerifyEmail ? clock.instant() : null;
            }
        }

        String newTheme = current.theme();
        if (theme != null) {
            newTheme = theme.strip();
            if (!THEMES.contains(newTheme)) {
                throw ApiException.badRequest(
                        "invalid_theme", "Тема должна быть одной из: " + String.join(", ", THEMES));
            }
        }

        try {
            users.updateProfile(userId, newDisplayName, newEmail, verifiedAt, newTheme);
            identities.updateEmail(userId, newEmail, verifiedAt);
        } catch (DuplicateKeyException e) {
            throw RegistrationService.emailTaken();
        }
        return require(userId);
    }

    public static ApiException notFound() {
        return ApiException.notFound("user_not_found", "Пользователь не найден");
    }
}
