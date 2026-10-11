package com.chattergame.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Профиль `dev`: письмо не уходит, содержимое попадает в лог, подтверждение емейла
 * проставляется вызывающим кодом автоматически ({@code D-079}).
 */
public class LoggingMailSender implements MailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingMailSender.class);

    @Override
    public void send(String email, String subject, String body) {
        log.info("mail skipped profile=dev subject={} to={}", subject, email);
    }
}
