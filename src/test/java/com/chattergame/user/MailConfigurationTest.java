package com.chattergame.user;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.javamail.JavaMailSender;

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

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
            .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
            .withUserConfiguration(MailConfiguration.class)
            .withPropertyValues(
                    "spring.mail.host=smtp.example.org",
                    "spring.mail.port=587",
                    "chattergame.mail.from=noreply@example.org",
                    "chattergame.mail.link-base=" + URL);

    @Test
    void devProfileNeverNeedsSmtp() {
        MailSender sender = new DevMailConfiguration().mailSender();

        assertThat(sender).isInstanceOf(LoggingMailSender.class);
        sender.send("player@example.org", "Подтверждение", "текст");
    }

    @Test
    void prodProfileFailsWithoutHost() {
        MailConfiguration configuration = new MailConfiguration();

        assertThatThrownBy(() -> configuration.javaMailSender(
                        "  ", 587, "", "", "noreply@example.org", true, URL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.mail.host");
    }

    @Test
    void prodProfileFailsWithoutFromAddress() {
        MailConfiguration configuration = new MailConfiguration();

        assertThatThrownBy(() -> configuration.javaMailSender(
                        "smtp.example.org", 587, "", "", " ", true, URL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chattergame.mail.from");
    }

    @Test
    void prodProfileFailsWithoutPublicUrl() {
        MailConfiguration configuration = new MailConfiguration();

        assertThatThrownBy(() -> configuration.javaMailSender(
                        "smtp.example.org", 587, "", "", "noreply@example.org", true, " "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chattergame.mail.link-base");
    }

    @Test
    void prodProfileBuildsSmtpSenderWhenConfigured() {
        MailSender sender = new MailConfiguration()
                .mailSender(new MailConfiguration()
                        .javaMailSender("smtp.example.org", 587, "user", "secret",
                                "noreply@example.org", true, URL),
                        "noreply@example.org");

        assertThat(sender).isInstanceOf(SmtpMailSender.class);
    }

    /**
     * Прод поднимается вместе с автоконфигурацией почты. Раньше бин типа {@link MailSender}
     * не отменял `MailSenderAutoConfiguration`, и она регистрировала свой `mailSender`:
     * контекст падал на конфликте имён ещё до проверок SMTP.
     */
    @Test
    void prodContextStartsWithMailAutoConfiguration() {
        runner.run(context -> assertThat(context)
                .hasNotFailed()
                .hasSingleBean(MailSender.class)
                .hasSingleBean(JavaMailSender.class));
    }
}
