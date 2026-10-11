package com.chattergame.user;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Пароль не попадает в логи на живом пути регистрации и входа. Статический скан
 * (`E02SecretsTest`) ловит явный `log.info(password)`, но не ловит значение, пришедшее
 * в логи косвенно — например, через исключение драйвера или сообщение валидации.
 * Здесь логи перехватываются и проверяются на само значение.
 */
class E02LogSecretsTest extends E02TestSupport {

    private static final String UNIQUE_PASSWORD = "секрет-в-логах-76п";

    @Test
    void passwordNeverAppearsInLogs() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        String login = unique("ЛогиПароль");
        String email = unique("логи") + "@example.org";

        try {
            register(login, UNIQUE_PASSWORD, email);
            loginRequest(login, UNIQUE_PASSWORD);
            loginRequest(login, OTHER_PASSWORD);
            // Сброс на существующий адрес проходит через `LoggingMailSender`, и в `dev`
            // содержимое письма оказывается в логе — именно там пароль не должен появиться.
            call(post("/me/password/reset"), null, Map.of("email", email));
        } finally {
            root.detachAppender(appender);
        }

        List<String> messages = new ArrayList<>();
        appender.list.forEach(event -> {
            messages.add(event.getFormattedMessage());
            if (event.getThrowableProxy() != null) {
                messages.add(event.getThrowableProxy().getMessage());
            }
        });

        assertThat(messages)
                .as("пароль в логе живёт дольше, чем инцидент")
                .noneMatch(message -> message != null && message.contains(UNIQUE_PASSWORD));
        assertThat(messages)
                .as("проверка осмысленна только если во время сценария что-то логировалось")
                .anyMatch(message -> message != null && message.contains("mail skipped"));
    }
}
