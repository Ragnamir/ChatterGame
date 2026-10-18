package com.chattergame.user;

import com.chattergame.util.ApiException;
import com.chattergame.util.Uuids;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Выдача и погашение токенов подтверждения адреса (`D-141`).
 *
 * <p>Токен адресован конкретному адресу. Смена почты делает прежние ссылки недействительными
 * сама по себе, без отдельного удаления строк: проверка сравнивает адрес из токена с текущим,
 * и письмо, ушедшее владельцу прежнего адреса, новый не подтверждает.
 */
@Service
public class EmailVerificationTokens {

    private static final Duration TTL = Duration.ofHours(24);

    private final EmailVerificationTokenRepository tokens;
    private final AppUserRepository users;
    private final AuthIdentityRepository identities;
    private final Clock clock;

    public EmailVerificationTokens(
            EmailVerificationTokenRepository tokens,
            AppUserRepository users,
            AuthIdentityRepository identities,
            Clock clock) {
        this.tokens = tokens;
        this.users = users;
        this.identities = identities;
        this.clock = clock;
    }

    @Transactional
    public String issue(UUID userId, String email) {
        Instant now = clock.instant();
        String token = Tokens.issue();
        tokens.markAllUsedForUser(userId, now);
        tokens.insert(new EmailVerificationToken(
                Uuids.v7(), userId, Tokens.hash(token), email, now, now.plus(TTL), null));
        return token;
    }

    /** Один ответ на все причины отказа: истёк, использован, чужой адрес (`D-079`). */
    @Transactional
    public void verify(String rawToken) {
        Instant now = clock.instant();
        EmailVerificationToken stored = tokens
                .findUsable(Tokens.hash(rawToken == null ? "" : rawToken), now)
                .orElseThrow(EmailVerificationTokens::invalid);
        AppUser user = users.findById(stored.userId())
                .filter(AppUser::isActive)
                .orElseThrow(EmailVerificationTokens::invalid);
        if (!stored.email().equals(user.email())) {
            throw invalid();
        }
        tokens.markUsed(stored.id(), now);
        users.markEmailVerified(user.id(), now);
        identities.updateEmail(user.id(), stored.email(), now);
    }

    private static ApiException invalid() {
        return ApiException.badRequest(
                "invalid_email_token", "Ссылка подтверждения недействительна или истекла");
    }
}
