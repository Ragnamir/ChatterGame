package com.chattergame.user;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Доставка и её счётчики (`D-143`): отказ транспорта не бросается наружу, но виден в
 * статистике по виду письма. Адрес и текст в лог не пишутся — это проверяет
 * {@code E02LogSecretsTest}; здесь важно, что исход считается и не теряется.
 */
class O01MailDeliveryTest {

    private static final MailTemplates.Message MESSAGE =
            MailTemplates.emailVerification("https://chattergame.example/verify-email?token=abc");

    @Test
    void successfulDeliveryIsCounted() {
        RecordingSender sender = new RecordingSender(null);
        MailDelivery delivery = new MailDelivery(sender);

        MailDelivery.Result result = delivery.send("email_verification", "player@example.org", MESSAGE);

        assertThat(result.delivered()).isTrue();
        assertThat(result.failure()).isNull();
        assertThat(sender.sent).containsExactly("player@example.org");
        assertThat(delivery.stats().get("email_verification").sent()).isEqualTo(1);
        assertThat(delivery.stats().get("email_verification").failed()).isZero();
    }

    @Test
    void transportFailureIsCountedAndNotThrown() {
        RecordingSender sender = new RecordingSender(new IllegalStateException("smtp down"));
        MailDelivery delivery = new MailDelivery(sender);

        MailDelivery.Result result = delivery.send("password_reset", "player@example.org", MESSAGE);

        assertThat(result.delivered()).isFalse();
        assertThat(result.failure()).isInstanceOf(IllegalStateException.class);
        assertThat(delivery.stats().get("password_reset").failed()).isEqualTo(1);
        assertThat(delivery.stats().get("password_reset").sent()).isZero();
    }

    @Test
    void kindsAreCountedSeparately() {
        RecordingSender sender = new RecordingSender(null);
        MailDelivery delivery = new MailDelivery(sender);

        delivery.send("email_verification", "a@example.org", MESSAGE);
        delivery.send("email_verification", "b@example.org", MESSAGE);
        delivery.send("password_reset", "a@example.org", MailTemplates.passwordReset("https://x/y"));

        assertThat(delivery.stats().get("email_verification").sent()).isEqualTo(2);
        assertThat(delivery.stats().get("password_reset").sent()).isEqualTo(1);
    }

    @Test
    void verificationAndResetTemplatesCarryTheLink() {
        assertThat(MESSAGE.text()).contains("https://chattergame.example/verify-email?token=abc");
        assertThat(MESSAGE.subject()).isNotBlank();
        assertThat(MailTemplates.passwordReset("https://chattergame.example/reset-password?token=abc").text())
                .contains("https://chattergame.example/reset-password?token=abc");
    }

    private static final class RecordingSender implements MailSender {

        private final RuntimeException failure;
        private final List<String> sent = new ArrayList<>();

        private RecordingSender(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public void send(String email, String subject, String body) {
            if (failure != null) {
                throw failure;
            }
            sent.add(email);
        }
    }
}
