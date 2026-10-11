package com.chattergame.user;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * Профиль `prod`: без настроенного SMTP приложение не стартует, потому что письма
 * подтверждения и сброса пароля — часть контракта, а не опция ({@code D-079}).
 */
@Configuration
@Profile("prod")
public class MailConfiguration {

    @Bean
    MailSender mailSender(
            @Value("${spring.mail.host:}") String host,
            @Value("${chattergame.mail.from:}") String from) {
        if (host.isBlank()) {
            throw new IllegalStateException(
                    "Профиль prod требует настроенный SMTP: задайте spring.mail.host (D-079)");
        }
        if (from.isBlank()) {
            throw new IllegalStateException(
                    "Профиль prod требует chattergame.mail.from (D-079)");
        }
        JavaMailSenderImpl javaMailSender = new JavaMailSenderImpl();
        javaMailSender.setHost(host);
        return new SmtpMailSender(javaMailSender, from);
    }
}
