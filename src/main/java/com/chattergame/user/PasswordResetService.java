package com.chattergame.user;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Сброс пароля по ссылке из письма.
 *
 * <p>Класс намеренно не транзакционный: отправка письма — блокирующий внешний вызов,
 * а внутри транзакции он держал бы соединение с БД десятки секунд и выедал пул
 * (`D-044`). Транзакционная часть вынесена в {@link PasswordService}.
 */
@Service
public class PasswordResetService {

    private static final int REQUESTS_PER_EMAIL = 3;
    private static final int REQUESTS_PER_CLIENT = 10;
    private static final Duration WINDOW = Duration.ofMinutes(15);

    private final AppUserRepository users;
    private final PasswordService passwords;
    private final RateLimiter rateLimiter;
    private final MailDelivery mail;
    private final String linkBase;

    public PasswordResetService(
            AppUserRepository users,
            PasswordService passwords,
            RateLimiter rateLimiter,
            MailDelivery mail,
            @Value("${chattergame.mail.link-base:http://localhost:5173}") String linkBase) {
        this.users = users;
        this.passwords = passwords;
        this.rateLimiter = rateLimiter;
        this.mail = mail;
        this.linkBase = linkBase;
    }

    /**
     * Ответ одинаков для существующего и несуществующего адреса (`D-079`): иначе эндпоинт
     * превращается в бесплатную проверку «зарегистрирован ли Вася». Ограничение частоты
     * считается по одним и тем же ключам в обоих случаях, поэтому по нему тоже нельзя
     * различить адреса.
     *
     * <p>Отказ доставки не меняет ответ: иначе по коду ошибки стало бы видно, что адрес
     * существует, — ровно то, что запрещает `D-079`.
     */
    public void requestReset(String rawEmail, String clientIp) {
        if (rawEmail == null || rawEmail.isBlank()) {
            return;
        }
        String email = Emails.normalize(rawEmail);
        rateLimiter.acquire("password-reset:email:" + email, REQUESTS_PER_EMAIL, WINDOW);
        if (clientIp != null && !clientIp.isBlank()) {
            rateLimiter.acquire("password-reset:client:" + clientIp, REQUESTS_PER_CLIENT, WINDOW);
        }
        users.findByEmail(email)
                .filter(AppUser::isActive)
                .flatMap(user -> passwords.issueResetToken(user.id()))
                .ifPresent(token -> mail.send(
                        "password_reset",
                        email,
                        MailTemplates.passwordReset(linkBase + "/reset-password?token=" + token)));
    }

    public void confirm(String token, String newPassword) {
        passwords.resetPassword(token, newPassword);
    }
}
