package com.chattergame.user;

import com.chattergame.game.GameMutationService;
import com.chattergame.llm.LlmCredentialService;
import com.chattergame.util.ApiException;
import com.chattergame.util.Uuids;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Удаление аккаунта: блокировка входа плюс анонимизация PII (`D-095`). Строка пользователя
 * не исчезает — на неё ссылаются персонажи и сообщения, и удаление сделало бы транскрипты
 * игр битыми. Убираются только данные, которые идентифицируют человека: логин, почта,
 * отображаемое имя.
 *
 * <p>Сессии и ключи LLM удаляются физически: это не часть игрового мира, а доступ, который
 * после закрытия аккаунта не должен существовать ни в каком виде. Игры мастера уходят
 * в архив и остаются читаемыми участникам, потому что мастерство не передаётся (`D-021`).
 */
@Service
public class AccountDeletionService {

    static final String ANONYMIZED_LOGIN_PREFIX = "deleted-";
    static final String ANONYMIZED_DISPLAY_NAME = "Удалённый пользователь";
    static final String ARCHIVE_REASON = "аккаунт мастера удалён";

    private final AppUserRepository users;
    private final AuthIdentityRepository identities;
    private final UserSessionRepository sessions;
    private final PasswordResetTokenRepository resetTokens;
    private final EmailVerificationTokenRepository verificationTokens;
    private final LlmCredentialService credentials;
    private final GameMutationService games;
    private final PasswordHasher passwordHasher;

    public AccountDeletionService(
            AppUserRepository users,
            AuthIdentityRepository identities,
            UserSessionRepository sessions,
            PasswordResetTokenRepository resetTokens,
            EmailVerificationTokenRepository verificationTokens,
            LlmCredentialService credentials,
            GameMutationService games,
            PasswordHasher passwordHasher) {
        this.users = users;
        this.identities = identities;
        this.sessions = sessions;
        this.resetTokens = resetTokens;
        this.verificationTokens = verificationTokens;
        this.credentials = credentials;
        this.games = games;
        this.passwordHasher = passwordHasher;
    }

    @Transactional
    public void deleteAccount(UUID userId, String currentPassword) {
        users.findById(userId).filter(AppUser::isActive).orElseThrow(UserService::notFound);

        AuthIdentity identity = identities.findLocalByUserId(userId).orElseThrow(UserService::notFound);
        if (identity.secretHash() == null
                || !passwordHasher.matches(currentPassword == null ? "" : currentPassword, identity.secretHash())) {
            throw ApiException.unauthorized("invalid_credentials", "Неверный пароль");
        }

        games.archiveAllOfMaster(userId, ARCHIVE_REASON);

        String anonymizedLogin = ANONYMIZED_LOGIN_PREFIX + Uuids.v7();
        users.anonymize(
                userId,
                anonymizedLogin,
                Logins.normalize(anonymizedLogin),
                ANONYMIZED_DISPLAY_NAME,
                AppUser.STATUS_DELETED);
        identities.anonymize(userId, anonymizedLogin);

        sessions.deleteAllForUser(userId);
        resetTokens.deleteAllForUser(userId);
        verificationTokens.deleteAllForUser(userId);
        credentials.deleteAllForUser(userId);
    }
}
