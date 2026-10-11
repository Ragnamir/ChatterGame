package com.chattergame.user;

/**
 * Отправка писем. В `dev` реализация пишет в лог и подтверждает емейл автоматически,
 * в `prod` требует настроенный SMTP ({@code D-078}, {@code D-079}).
 */
public interface MailSender {

    void send(String email, String subject, String body);
}
