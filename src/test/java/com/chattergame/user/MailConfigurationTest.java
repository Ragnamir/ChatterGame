package com.chattergame.user;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * `D-079`, `D-142`: в `dev` почта не требуется, в `prod` без SMTP и без абсолютного адреса
 * приложения письмо бесполезно, поэтому приложение обязано не стартовать.
 * Проверяется на самой конфигурации, без поднятия контекста: цена ошибки здесь — падение
 * продакшна на старте.
 */
class MailConfigurationTest {

    private static final String URL = "https://chattergame.example";

    @Test
    void devProfileNeverNeedsSmtp() {
        MailSender sender = new DevMailConfiguration().mailSender();

        assertThat(sender).isInstanceOf(LoggingMailSender.class);
        sender.send("player@example.org", "Подтверждение", "текст");
    }

    @Test
    void prodProfileFailsWithoutHost() {
        MailConfiguration configuration = new MailConfiguration();

        assertThatThrownBy(() -> configuration.mailSender(
                        "  ", 587, "", "", "noreply@example.org", true, URL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.mail.host");
    }

    @Test
    void prodProfileFailsWithoutFromAddress() {
        MailConfiguration configuration = new MailConfiguration();

        assertThatThrownBy(() -> configuration.mailSender(
                        "smtp.example.org", 587, "", "", " ", true, URL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chattergame.mail.from");
    }

    @Test
    void prodProfileFailsWithoutPublicUrl() {
        MailConfiguration configuration = new MailConfiguration();

        assertThatThrownBy(() -> configuration.mailSender(
                        "smtp.example.org", 587, "", "", "noreply@example.org", true, " "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chattergame.mail.link-base");
    }

    @Test
    void prodProfileBuildsSmtpSenderWhenConfigured() {
        MailSender sender = new MailConfiguration()
                .mailSender("smtp.example.org", 587, "user", "secret", "noreply@example.org", true, URL);

        assertThat(sender).isInstanceOf(SmtpMailSender.class);
    }
}
