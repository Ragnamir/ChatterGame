package com.chattergame.user;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Подтверждение адреса (`O01`, `D-141`): письмо после регистрации, одноразовая ссылка,
 * привязка к адресу, повторная отправка с лимитом и сброс подтверждения при смене почты.
 *
 * <p>Профиль `dev` автоподтверждает адрес (`D-078`), поэтому тест выключает
 * автоподтверждение свойством: иначе письма не было бы вовсе, и проверять было бы нечего.
 * Письмо перехватывается подставным `MailSender` — токен существует только в теле письма,
 * в БД лежит хеш.
 */
@TestPropertySource(properties = "chattergame.mail.auto-verify-email=false")
class O01EmailVerificationTest extends E02TestSupport {

    private static final Pattern TOKEN = Pattern.compile("token=([A-Za-z0-9_-]+)");

    @TestConfiguration
    static class CapturingMail {

        @Bean
        @Primary
        CapturingMailSender capturingMailSender() {
            return new CapturingMailSender();
        }
    }

    static class CapturingMailSender implements MailSender {

        private final List<Mail> sent = new CopyOnWriteArrayList<>();

        @Override
        public void send(String email, String subject, String body) {
            sent.add(new Mail(email, subject, body));
        }

        List<Mail> to(String email) {
            return sent.stream().filter(mail -> mail.email().equalsIgnoreCase(email)).toList();
        }

        void clear() {
            sent.clear();
        }

        record Mail(String email, String subject, String body) {
        }
    }

    @Autowired
    private CapturingMailSender mails;

    @BeforeEach
    void resetMails() {
        mails.clear();
    }

    private static String email(String prefix) {
        return unique(prefix).toLowerCase() + "@example.org";
    }

    private String tokenFromLastMail(String email) {
        List<CapturingMailSender.Mail> sent = mails.to(email);
        assertThat(sent).as("письмо подтверждения отправлено").isNotEmpty();
        Matcher matcher = TOKEN.matcher(sent.get(sent.size() - 1).body());
        assertThat(matcher.find()).as("в письме есть токен").isTrue();
        return matcher.group(1);
    }

    private int verify(String token) throws Exception {
        var result = call(post("/auth/verify-email"), null, Map.of("token", token));
        return result.getResponse().getStatus();
    }

    private Instant verifiedAt(String login) {
        return jdbc.queryForObject(
                "SELECT email_verified_at FROM app_user WHERE login_normalized = ?",
                (rs, rowNum) -> AppUserRepository.instant(rs, "email_verified_at"),
                loginNormalized(login));
    }

    private String emailOf(String login) {
        return jdbc.queryForObject(
                "SELECT email FROM app_user WHERE login_normalized = ?",
                String.class,
                loginNormalized(login));
    }

    private int resend(String token, String clientIp) throws Exception {
        var result = mvc.perform(post("/me/email/verification")
                        .cookie(new jakarta.servlet.http.Cookie(SessionCookie.NAME, token))
                        .with(request -> {
                            request.setRemoteAddr(clientIp);
                            return request;
                        }))
                .andReturn();
        return result.getResponse().getStatus();
    }

    @Test
    void registrationSendsLinkAndLinkConfirmsAddress() throws Exception {
        String login = unique("Подтверждение");
        String address = email("verify");
        String token = tokenOf(register(login, PASSWORD, address));

        assertThat(verifiedAt(login)).as("до перехода по ссылке адрес не подтверждён").isNull();
        String linkToken = tokenFromLastMail(address);

        assertThat(verify(linkToken)).isEqualTo(204);
        assertThat(verifiedAt(login)).as("адрес подтверждён").isNotNull();
        Instant identityVerifiedAt = jdbc.queryForObject(
                "SELECT verified_at FROM auth_identity WHERE user_id = "
                        + "(SELECT id FROM app_user WHERE login_normalized = ?)",
                (rs, rowNum) -> AppUserRepository.instant(rs, "verified_at"),
                loginNormalized(login));
        assertThat(identityVerifiedAt)
                .as("подтверждение относится к адресу и лежит рядом с ним")
                .isNotNull();
        assertThat(token).isNotNull();
    }

    @Test
    void linkIsSingleUse() throws Exception {
        String login = unique("Одноразово");
        String address = email("single");
        register(login, PASSWORD, address);
        String linkToken = tokenFromLastMail(address);

        assertThat(verify(linkToken)).isEqualTo(204);
        assertThat(verify(linkToken)).as("повторная ссылка уже недействительна").isEqualTo(400);
    }

    @Test
    void linkIssuedForPreviousAddressDoesNotConfirmNewOne() throws Exception {
        String login = unique("СменаАдреса");
        String first = email("first");
        String second = email("second");
        String session = tokenOf(register(login, PASSWORD, first));
        String staleToken = tokenFromLastMail(first);

        var updated = call(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/me"),
                session,
                Map.of("email", second));
        assertThat(updated.getResponse().getStatus()).isEqualTo(200);
        assertThat(verifiedAt(login)).as("новый адрес не подтверждён прежней ссылкой").isNull();

        assertThat(verify(staleToken)).as("ссылка адресована прежнему адресу").isEqualTo(400);
        assertThat(verify(tokenFromLastMail(second))).isEqualTo(204);
        assertThat(verifiedAt(login)).isNotNull();
    }

    /**
     * Удаление адреса из профиля не выдаёт новое письмо, поэтому прежняя ссылка остаётся
     * единственной живой. Без сверки адреса она вернула бы удалённый адрес в профиль и
     * подтвердила бы его — то есть адресом снова стал бы ящик, который человек убрал.
     */
    @Test
    void linkDoesNotConfirmAddressRemovedFromProfile() throws Exception {
        String login = unique("БезАдреса");
        String address = email("removed");
        String session = tokenOf(register(login, PASSWORD, address));
        String staleToken = tokenFromLastMail(address);

        var updated = call(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/me"),
                session,
                Map.of("email", ""));
        assertThat(updated.getResponse().getStatus()).isEqualTo(200);
        assertThat(emailOf(login)).isNull();

        assertThat(verify(staleToken)).as("ссылка не возвращает удалённый адрес").isEqualTo(400);
        assertThat(emailOf(login)).isNull();
        assertThat(verifiedAt(login)).isNull();
    }

    @Test
    void resendIssuesNewLinkAndRevokesPreviousOne() throws Exception {
        String login = unique("Повтор");
        String address = email("resend");
        String session = tokenOf(register(login, PASSWORD, address));
        String first = tokenFromLastMail(address);

        assertThat(resend(session, "10.90.1.1")).isEqualTo(202);
        String second = tokenFromLastMail(address);
        assertThat(second).isNotEqualTo(first);

        assertThat(verify(first)).as("прежняя ссылка погашена новым письмом").isEqualTo(400);
        assertThat(verify(second)).isEqualTo(204);
    }

    @Test
    void resendIsRateLimitedPerUser() throws Exception {
        String login = unique("Лимит");
        String address = email("limit");
        String session = tokenOf(register(login, PASSWORD, address));
        String ip = "10.90.2." + (1 + (int) (Math.random() * 200));

        assertThat(resend(session, ip)).isEqualTo(202);
        assertThat(resend(session, ip)).isEqualTo(202);
        assertThat(resend(session, ip)).isEqualTo(202);
        assertThat(resend(session, ip)).as("четвёртое письмо за окно отклонено").isEqualTo(429);
        assertThat(resend(session, ip)).isEqualTo(429);
    }

    @Test
    void resendRejectsVerifiedAddress() throws Exception {
        String login = unique("УжеПодтверждён");
        String address = email("already");
        String session = tokenOf(register(login, PASSWORD, address));
        assertThat(verify(tokenFromLastMail(address))).isEqualTo(204);

        assertThat(resend(session, "10.90.3.1")).as("подтверждённому адресу письмо не нужно").isEqualTo(409);
    }

    @Test
    void resendWithoutAddressIsRejected() throws Exception {
        String login = unique("БезПочты");
        String session = tokenOf(register(login, PASSWORD, null));

        assertThat(resend(session, "10.90.4.1")).isEqualTo(400);
        assertThat(mails.to("")).isEmpty();
    }

    @Test
    void tokenIsStoredHashed() throws Exception {
        String login = unique("Хеш");
        String address = email("hash");
        register(login, PASSWORD, address);
        String linkToken = tokenFromLastMail(address);

        Long stored = jdbc.queryForObject(
                "SELECT count(*) FROM email_verification_token WHERE token_hash = ?",
                Long.class,
                Tokens.hash(linkToken));
        Long raw = jdbc.queryForObject(
                "SELECT count(*) FROM email_verification_token WHERE token_hash = ?",
                Long.class,
                linkToken);

        assertThat(stored).as("в БД лежит хеш токена").isEqualTo(1);
        assertThat(raw).as("сам токен в БД не хранится").isZero();
    }

    @Test
    void expiredLinkIsRejected() throws Exception {
        String login = unique("Просрочка");
        String address = email("expired");
        register(login, PASSWORD, address);
        String linkToken = tokenFromLastMail(address);

        jdbc.update(
                "UPDATE email_verification_token SET expires_at = now() - interval '1 minute' "
                        + "WHERE user_id = (SELECT id FROM app_user WHERE login_normalized = ?)",
                loginNormalized(login));

        assertThat(verify(linkToken)).isEqualTo(400);
        assertThat(verifiedAt(login)).isNull();
    }

    @Test
    void deletionRemovesVerificationTokens() throws Exception {
        String login = unique("Удаление");
        String address = email("delete");
        String session = tokenOf(register(login, PASSWORD, address));
        assertThat(tokenFromLastMail(address)).isNotBlank();

        var result = call(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/me"),
                session,
                Map.of("password", PASSWORD));
        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(countRows(
                        "SELECT count(*) FROM email_verification_token WHERE user_id = "
                                + "(SELECT id FROM app_user WHERE login_normalized = ?)",
                        loginNormalized(login)))
                .isZero();
    }
}
