package com.chattergame.user;

import com.chattergame.util.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * Письма подтверждения адреса (`O01`, `D-141`).
 *
 * <p>Класс не транзакционный: отправка письма — блокирующий внешний вызов, а внутри
 * транзакции он держал бы соединение с БД (`D-044`). Токен выдаёт
 * {@link EmailVerificationTokens} своей транзакцией, письмо уходит после неё.
 *
 * <p>Ошибка доставки не отменяет регистрацию: аккаунт уже создан, и человек может
 * запросить письмо повторно из профиля. Поэтому {@link #sendIfUnverified} исход не
 * пробрасывает, а {@link #resend} — пробрасывает: там отказ должен дойти до человека.
 */
@Service
public class EmailVerificationService {

    private static final int REQUESTS_PER_USER = 3;
    private static final int REQUESTS_PER_CLIENT = 10;
    private static final Duration WINDOW = Duration.ofMinutes(15);

    private final EmailVerificationTokens tokens;
    private final AppUserRepository users;
    private final RateLimiter rateLimiter;
    private final MailDelivery mail;
    private final String linkBase;
    private final boolean autoVerifyEmail;

    public EmailVerificationService(
            EmailVerificationTokens tokens,
            AppUserRepository users,
            RateLimiter rateLimiter,
            MailDelivery mail,
            @Value("${chattergame.mail.link-base:http://localhost:5173}") String linkBase,
            @Value("${chattergame.mail.auto-verify-email:false}") boolean autoVerifyEmail) {
        this.tokens = tokens;
        this.users = users;
        this.rateLimiter = rateLimiter;
        this.mail = mail;
        this.linkBase = linkBase;
        this.autoVerifyEmail = autoVerifyEmail;
    }

    /**
     * Письмо после регистрации или смены адреса. В `dev` подтверждение проставляется
     * автоматически, и письмо не нужно (`D-078`).
     */
    public void sendIfUnverified(UUID userId) {
        if (autoVerifyEmail) {
            return;
        }
        users.findById(userId)
                .filter(AppUser::isActive)
                .filter(user -> user.email() != null && !user.emailVerified())
                .ifPresent(user -> send(user));
    }

    /** Повторная отправка из профиля: лимит по человеку и по клиенту, как у сброса (`D-079`). */
    public void resend(UUID userId, String clientIp) {
        AppUser user = users.findById(userId)
                .filter(AppUser::isActive)
                .orElseThrow(UserService::notFound);
        if (user.email() == null || user.email().isBlank()) {
            throw ApiException.badRequest("email_not_set", "В профиле не указана почта");
        }
        if (user.emailVerified()) {
            throw ApiException.conflict("email_already_verified", "Адрес уже подтверждён");
        }
        rateLimiter.acquire("email-verify:user:" + userId, REQUESTS_PER_USER, WINDOW);
        if (clientIp != null && !clientIp.isBlank()) {
            rateLimiter.acquire("email-verify:client:" + clientIp, REQUESTS_PER_CLIENT, WINDOW);
        }
        MailDelivery.Result result = send(user);
        if (!result.delivered()) {
            throw ApiException.conflict(
                    "mail_unavailable", "Почта недоступна: письмо не отправлено, попробуйте позже");
        }
    }

    public void verify(String token) {
        tokens.verify(token);
    }

    private MailDelivery.Result send(AppUser user) {
        String token = tokens.issue(user.id(), user.email());
        return mail.send(
                "email_verification",
                user.email(),
                MailTemplates.emailVerification(linkBase + "/verify-email?token=" + token));
    }
}
