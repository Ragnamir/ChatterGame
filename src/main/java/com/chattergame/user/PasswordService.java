package com.chattergame.user;

import com.chattergame.util.ApiException;
import com.chattergame.util.Uuids;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Всё, что устанавливает пароль: смена из профиля и сброс по ссылке. Обе операции
 * заканчиваются одним и тем же — старый пароль перестаёт работать, неиспользованные
 * токены сброса гасятся, все сессии обрываются (`D-079`, `D-096`).
 *
 * <p>Держать это в одном месте, а не в двух сервисах, важно: разъехавшись, сброс и смена
 * начнут по-разному вести себя с сессиями, и дыру заметят уже в проде.
 */
@Service
public class PasswordService {

    private static final Duration RESET_TOKEN_TTL = Duration.ofMinutes(60);

    private final AuthIdentityRepository identities;
    private final PasswordResetTokenRepository resetTokens;
    private final UserSessionRepository sessions;
    private final PasswordHasher hasher;
    private final Clock clock;

    public PasswordService(
            AuthIdentityRepository identities,
            PasswordResetTokenRepository resetTokens,
            UserSessionRepository sessions,
            PasswordHasher hasher,
            Clock clock) {
        this.identities = identities;
        this.resetTokens = resetTokens;
        this.sessions = sessions;
        this.hasher = hasher;
        this.clock = clock;
    }

    /** Выдаёт токен сброса, гася прежние: две живые ссылки на один аккаунт не нужны (`D-079`). */
    @Transactional
    public Optional<String> issueResetToken(java.util.UUID userId) {
        Instant now = clock.instant();
        String token = Tokens.issue();
        resetTokens.markAllUsedForUser(userId, now);
        resetTokens.insert(new PasswordResetToken(
                Uuids.v7(), userId, Tokens.hash(token), now, now.plus(RESET_TOKEN_TTL), null));
        return Optional.of(token);
    }

    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        validatePassword(newPassword);
        Instant now = clock.instant();
        PasswordResetToken stored = resetTokens
                .findUsable(Tokens.hash(rawToken == null ? "" : rawToken), now)
                .orElseThrow(() -> ApiException.badRequest(
                        "invalid_reset_token", "Ссылка сброса недействительна или истекла"));
        applyNewPassword(stored.userId(), newPassword, now);
        resetTokens.markUsed(stored.id(), now);
    }

    @Transactional
    public int changePassword(java.util.UUID userId, String currentPassword, String newPassword) {
        validatePassword(newPassword);
        AuthIdentity identity = identities.findLocalByUserId(userId).orElseThrow(() -> ApiException
                .notFound("identity_not_found", "Локальный креденшал не найден"));
        if (!hasher.matches(currentPassword == null ? "" : currentPassword, identity.secretHash())) {
            throw ApiException.unauthorized("invalid_credentials", "Неверный текущий пароль");
        }
        Instant now = clock.instant();
        applyNewPassword(userId, newPassword, now);
        return sessions.revokeAllForUser(userId, now);
    }

    /** Порядок неизменен: сначала гасим токены и сессии, потом появляется новый хеш. */
    private void applyNewPassword(java.util.UUID userId, String newPassword, Instant now) {
        identities.updateSecretHash(userId, hasher.hash(newPassword));
        resetTokens.markAllUsedForUser(userId, now);
        sessions.revokeAllForUser(userId, now);
    }

    static void validatePassword(String rawPassword) {
        if (rawPassword == null || !PasswordHasher.lengthAllowed(rawPassword)) {
            throw ApiException.badRequest(
                    "invalid_password",
                    "Пароль должен быть от " + PasswordHasher.MIN_LENGTH + " до "
                            + PasswordHasher.MAX_LENGTH + " символов");
        }
    }
}
