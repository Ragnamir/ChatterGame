package com.chattergame.user;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * Профиль `prod`: без настроенного SMTP приложение не стартует, потому что письма
 * подтверждения и сброса пароля — часть контракта, а не опция ({@code D-079}).
 *
 * <p>Учётные данные берутся только из окружения (`D-079`, `D-144`): локальный релей
 * обходится без них, а публичный провайдер требует. Пустой логин означает «без
 * аутентификации», а не «забыли настроить»: так же ведёт себя локальный постфикс.
 */
@Configuration
@Profile("prod")
public class MailConfiguration {

    private static final String TIMEOUT_MS = "10000";

    /**
     * Бин называется `javaMailSender` и имеет тип {@link JavaMailSender}, чтобы
     * `MailSenderAutoConfiguration` отступил: его условие — «нет бина JavaMailSender».
     * Бин с другим типом не отменял автоконфигурацию, и она пыталась зарегистрировать
     * свой `mailSender` — контекст прода падал на конфликте имён.
     */
    @Bean
    JavaMailSender javaMailSender(
            @Value("${spring.mail.host:}") String host,
            @Value("${spring.mail.port:587}") int port,
            @Value("${spring.mail.username:}") String username,
            @Value("${spring.mail.password:}") String password,
            @Value("${chattergame.mail.from:}") String from,
            @Value("${chattergame.mail.starttls:true}") boolean starttls,
            @Value("${chattergame.mail.link-base:}") String linkBase) {
        if (host.isBlank()) {
            throw new IllegalStateException(
                    "Профиль prod требует настроенный SMTP: задайте spring.mail.host (D-079)");
        }
        if (from.isBlank()) {
            throw new IllegalStateException(
                    "Профиль prod требует chattergame.mail.from (D-079)");
        }
        if (linkBase.isBlank()) {
            // Ссылка в письме должна быть абсолютной: из почтового клиента относительный
            // путь никуда не ведёт (D-142).
            throw new IllegalStateException(
                    "Профиль prod требует chattergame.mail.link-base (D-142)");
        }
        JavaMailSenderImpl javaMailSender = new JavaMailSenderImpl();
        javaMailSender.setHost(host);
        javaMailSender.setPort(port);
        javaMailSender.setDefaultEncoding("UTF-8");
        if (!username.isBlank()) {
            javaMailSender.setUsername(username);
            javaMailSender.setPassword(password);
        }
        javaMailSender.getJavaMailProperties().setProperty("mail.smtp.auth", Boolean.toString(!username.isBlank()));
        javaMailSender.getJavaMailProperties().setProperty("mail.smtp.starttls.enable", Boolean.toString(starttls));
        // Таймауты обязательны: без них недоступный SMTP держит поток запроса до таймаута ОС,
        // и регистрация «висит» вместо того, чтобы отдать ответ (`D-044` по духу).
        javaMailSender.getJavaMailProperties().setProperty("mail.smtp.connectiontimeout", TIMEOUT_MS);
        javaMailSender.getJavaMailProperties().setProperty("mail.smtp.timeout", TIMEOUT_MS);
        javaMailSender.getJavaMailProperties().setProperty("mail.smtp.writetimeout", TIMEOUT_MS);
        return javaMailSender;
    }

    @Bean
    MailSender mailSender(JavaMailSender javaMailSender,
            @Value("${chattergame.mail.from:}") String from) {
        return new SmtpMailSender(javaMailSender, from);
    }
}
