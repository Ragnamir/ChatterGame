package com.chattergame.user;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * `D-079`: в `dev` почта не требуется, в `prod` без SMTP приложение обязано не стартовать.
 * Проверяется на самой конфигурации, без поднятия контекста: цена ошибки здесь — падение
 * продакшна на старте.
 */
class MailConfigurationTest {

    @Test
    void devProfileNeverNeedsSmtp() {
        MailSender sender = new DevMailConfiguration().mailSender();

        assertThat(sender).isInstanceOf(LoggingMailSender.class);
        sender.send("player@example.org", "Подтверждение", "текст");
    }

    @Test
    void prodProfileFailsWithoutHost() {
        MailConfiguration configuration = new MailConfiguration();

        assertThatThrownBy(() -> configuration.mailSender("  ", "noreply@example.org"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.mail.host");
    }

    @Test
    void prodProfileFailsWithoutFromAddress() {
        MailConfiguration configuration = new MailConfiguration();

        assertThatThrownBy(() -> configuration.mailSender("smtp.example.org", " "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chattergame.mail.from");
    }

    @Test
    void prodProfileBuildsSmtpSenderWhenConfigured() {
        MailSender sender = new MailConfiguration()
                .mailSender("smtp.example.org", "noreply@example.org");

        assertThat(sender).isInstanceOf(SmtpMailSender.class);
    }
}
