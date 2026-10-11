package com.chattergame.user;

import com.chattergame.util.Uuids;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Серверные сессии. Срок 30 дней и скольжение (`D-096`): месяц асинхронной игры без
 * повторного входа, но при этом сессия не живёт вечно.
 *
 * <p>Скольжение записывается не на каждый запрос, а раз в час: при ленте, которая
 * опрашивается постоянно, `UPDATE` на каждый запрос — это нагрузка на диск ради
 * изменения, которое влияет только через сутки.
 */
@Service
public class SessionService {

    public static final Duration LIFETIME = Duration.ofDays(30);

    private static final Duration TOUCH_AFTER = Duration.ofHours(1);
    private static final int USER_AGENT_MAX = 255;

    private final UserSessionRepository sessions;
    private final AppUserRepository users;
    private final Clock clock;

    public SessionService(UserSessionRepository sessions, AppUserRepository users, Clock clock) {
        this.sessions = sessions;
        this.users = users;
        this.clock = clock;
    }

    public IssuedSession issue(UUID userId, String userAgent) {
        Instant now = clock.instant();
        String token = Tokens.issue();
        UserSession session = new UserSession(
                Uuids.v7(),
                userId,
                Tokens.hash(token),
                shorten(userAgent),
                now,
                now,
                now.plus(LIFETIME),
                null);
        sessions.insert(session);
        return new IssuedSession(token, session.id(), session.expiresAt());
    }

    /** Возвращает пользователя, если сессия жива. Иначе пусто — вызывающий решает, что отдать. */
    public Optional<ResolvedSession> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        Optional<UserSession> found = sessions.findActive(Tokens.hash(token), now);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        UserSession session = found.get();
        Optional<AppUser> user = users.findById(session.userId()).filter(AppUser::isActive);
        if (user.isEmpty()) {
            return Optional.empty();
        }
        boolean refreshed = now.isAfter(session.lastSeenAt().plus(TOUCH_AFTER));
        if (refreshed) {
            sessions.touch(session.id(), now, now.plus(LIFETIME));
        }
        Instant expiresAt = refreshed ? now.plus(LIFETIME) : session.expiresAt();
        return Optional.of(new ResolvedSession(user.get(), session.id(), expiresAt, refreshed));
    }

    public void revoke(String token) {
        if (token != null && !token.isBlank()) {
            sessions.revokeByTokenHash(Tokens.hash(token), clock.instant());
        }
    }

    /** Смена и сброс пароля обрывают всё, включая текущее устройство (`D-096`). */
    public int revokeAll(UUID userId) {
        return sessions.revokeAllForUser(userId, clock.instant());
    }

    public int activeCount(UUID userId) {
        return sessions.countActiveForUser(userId, clock.instant());
    }

    private static String shorten(String userAgent) {
        if (userAgent == null) {
            return null;
        }
        return userAgent.length() <= USER_AGENT_MAX ? userAgent : userAgent.substring(0, USER_AGENT_MAX);
    }

    public record IssuedSession(String token, UUID sessionId, Instant expiresAt) {
    }

    public record ResolvedSession(AppUser user, UUID sessionId, Instant expiresAt, boolean refreshed) {
    }
}
