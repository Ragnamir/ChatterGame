package com.chattergame.user;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Профиль `dev`: письма уходят в лог, SMTP не требуется (`D-079`). */
@Configuration
@Profile("dev")
public class DevMailConfiguration {

    @Bean
    MailSender mailSender() {
        return new LoggingMailSender();
    }
}
