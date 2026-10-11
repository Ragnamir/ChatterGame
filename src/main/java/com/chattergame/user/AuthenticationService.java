package com.chattergame.user;

import com.chattergame.util.ApiException;
import org.springframework.stereotype.Service;

/**
 * Вход по логину и паролю. Ответ на неверный логин, неверный пароль и удалённый аккаунт
 * один и тот же: иначе форма входа становится бесплатным способом узнать, кто
 * зарегистрирован.
 *
 * <p>Когда пользователь не найден, хеш всё равно проверяется — на заглушке. Без этого
 * ответ на существующий логин занимал бы десятки миллисекунд, а на несуществующий
 * возвращался мгновенно, и разница выдавала бы наличие аккаунта вместо проверки хеша.
 */
@Service
public class AuthenticationService {

    private static final String DUMMY_PASSWORD = "chattergame-timing-equalizer";

    private final AppUserRepository users;
    private final AuthIdentityRepository identities;
    private final PasswordHasher hasher;
    private final SessionService sessions;
    private final String dummyHash;

    public AuthenticationService(
            AppUserRepository users,
            AuthIdentityRepository identities,
            PasswordHasher hasher,
            SessionService sessions) {
        this.users = users;
        this.identities = identities;
        this.hasher = hasher;
        this.sessions = sessions;
        this.dummyHash = hasher.hash(DUMMY_PASSWORD);
    }

    public Authenticated login(String rawLogin, String rawPassword, String userAgent) {
        String normalized = Logins.normalize(rawLogin == null ? "" : rawLogin);
        String password = rawPassword == null ? "" : rawPassword;

        AppUser user = users.findByLoginNormalized(normalized).filter(AppUser::isActive).orElse(null);
        if (user == null) {
            hasher.matches(password, dummyHash);
            throw invalidCredentials();
        }
        AuthIdentity identity = identities.findLocalByUserId(user.id()).orElse(null);
        if (identity == null || !hasher.matches(password, identity.secretHash())) {
            throw invalidCredentials();
        }
        SessionService.IssuedSession session = sessions.issue(user.id(), userAgent);
        return new Authenticated(user, session.token());
    }

    private static ApiException invalidCredentials() {
        return ApiException.unauthorized("invalid_credentials", "Неверный логин или пароль");
    }

    public record Authenticated(AppUser user, String sessionToken) {
    }
}
