package com.chattergame.user;

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
    private static final String SUBJECT = "Сброс пароля ChatterGame";

    private final AppUserRepository users;
    private final PasswordService passwords;
    private final RateLimiter rateLimiter;
    private final MailSender mailSender;

    public PasswordResetService(
            AppUserRepository users,
            PasswordService passwords,
            RateLimiter rateLimiter,
            MailSender mailSender) {
        this.users = users;
        this.passwords = passwords;
        this.rateLimiter = rateLimiter;
        this.mailSender = mailSender;
    }

    /**
     * Ответ одинаков для существующего и несуществующего адреса (`D-079`): иначе эндпоинт
     * превращается в бесплатную проверку «зарегистрирован ли Вася». Ограничение частоты
     * считается по одним и тем же ключам в обоих случаях, поэтому по нему тоже нельзя
     * различить адреса.
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
                .ifPresent(token -> mailSender.send(email, SUBJECT, body(token)));
    }

    public void confirm(String token, String newPassword) {
        passwords.resetPassword(token, newPassword);
    }

    private static String body(String token) {
        return "Чтобы задать новый пароль, откройте ссылку:\n\n/reset-password?token=" + token
                + "\n\nЕсли вы не запрашивали сброс, ничего делать не нужно.";
    }
}
