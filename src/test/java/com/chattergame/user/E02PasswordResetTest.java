package com.chattergame.user;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Сброс пароля (`D-079`): одноразовый токен, TTL, инвалидация при смене пароля, rate limit
 * и одинаковый ответ на существующий и несуществующий адрес.
 *
 * Письмо перехватывается подставным `MailSender`, а не моком: токен существует только в теле
 * письма (в БД лежит хеш), поэтому без перехвата проверить сценарий невозможно. Подставной
 * отправитель ведёт себя как настоящий — пишет письмо в список.
 *
 * `clientIp` в `MockMvc` общий для всех запросов, поэтому тесты, где лимит не проверяется,
 * подставляют свой адрес: иначе они наступали бы на счётчик друг друга.
 */
class E02PasswordResetTest extends E02TestSupport {

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

    private String requestReset(String email, String clientIp) throws Exception {
        var result = mvc.perform(post("/me/password/reset")
                        .with(request -> {
                            request.setRemoteAddr(clientIp);
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email))))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(202);
        return result.getResponse().getContentAsString();
    }

    private String tokenFromLastMail(String email) {
        List<CapturingMailSender.Mail> mails = this.mails.to(email);
        assertThat(mails).as("письмо со ссылкой сброса отправлено").isNotEmpty();
        Matcher matcher = TOKEN.matcher(mails.get(mails.size() - 1).body());
        assertThat(matcher.find()).as("в письме есть токен").isTrue();
        return matcher.group(1);
    }

    private static String ip(int third) {
        return "10.42." + third + ".1";
    }

    @Test
    void resetTokenIsSingleUse() throws Exception {
        String login = unique("Однораз");
        String email = unique("однораз") + "@example.org";
        register(login, PASSWORD, email);

        requestReset(email, ip(1));
        String token = tokenFromLastMail(email);

        var first = call(post("/me/password/reset/confirm"), null,
                Map.of("token", token, "newPassword", OTHER_PASSWORD));
        assertThat(first.getResponse().getStatus()).isEqualTo(204);

        var second = call(post("/me/password/reset/confirm"), null,
                Map.of("token", token, "newPassword", "третий-пароль-99"));
        assertThat(second.getResponse().getStatus())
                .as("токен одноразовый")
                .isEqualTo(400);
        assertThat(bodyOf(second)).contains("invalid_reset_token");
        assertThat(loginAndGetToken(login, OTHER_PASSWORD)).isNotNull();
    }

    @Test
    void resetTokenDoesNotSurvivePasswordChange() throws Exception {
        String login = unique("СменаГасит");
        String email = unique("сменагасит") + "@example.org";
        register(login, PASSWORD, email);
        String token = loginAndGetToken(login, PASSWORD);

        requestReset(email, ip(2));
        String resetToken = tokenFromLastMail(email);

        var changed = call(post("/me/password"), token,
                Map.of("currentPassword", PASSWORD, "newPassword", OTHER_PASSWORD));
        assertThat(changed.getResponse().getStatus()).isEqualTo(204);

        var afterChange = call(post("/me/password/reset/confirm"), null,
                Map.of("token", resetToken, "newPassword", "поздний-пароль-55"));
        assertThat(afterChange.getResponse().getStatus())
                .as("смена пароля гасит выданные токены сброса (D-079)")
                .isEqualTo(400);
    }

    @Test
    void responseIsIdenticalForKnownAndUnknownEmail() throws Exception {
        String email = unique("известный") + "@example.org";
        register(unique("Известный"), PASSWORD, email);

        String forKnown = requestReset(email, ip(3));
        String forUnknown = requestReset(unique("неизвестный") + "@example.org", ip(3));

        assertThat(forKnown)
                .as("ответ не должен выдавать, зарегистрирован ли адрес")
                .isEqualTo(forUnknown);
        assertThat(mails.to(email)).hasSize(1);
    }

    @Test
    void noMailIsSentToUnknownAddress() throws Exception {
        String unknown = unique("никому") + "@example.org";

        requestReset(unknown, ip(4));

        assertThat(mails.to(unknown)).isEmpty();
    }

    @Test
    void resetTokenExpires() throws Exception {
        String login = unique("Истекает");
        String email = unique("истекает") + "@example.org";
        register(login, PASSWORD, email);
        requestReset(email, ip(5));
        String token = tokenFromLastMail(email);

        jdbc.update("UPDATE password_reset_token SET expires_at = now() - interval '1 minute'");

        var expired = call(post("/me/password/reset/confirm"), null,
                Map.of("token", token, "newPassword", OTHER_PASSWORD));

        assertThat(expired.getResponse().getStatus()).isEqualTo(400);
        assertThat(bodyOf(expired)).contains("invalid_reset_token");
    }

    @Test
    void resetInvalidatesEverySession() throws Exception {
        String login = unique("СессииГасит");
        String email = unique("сессиигасит") + "@example.org";
        register(login, PASSWORD, email);
        String phone = loginAndGetToken(login, PASSWORD);
        String laptop = loginAndGetToken(login, PASSWORD);

        requestReset(email, ip(6));
        String token = tokenFromLastMail(email);
        assertThat(call(post("/me/password/reset/confirm"), null,
                        Map.of("token", token, "newPassword", OTHER_PASSWORD))
                .getResponse()
                .getStatus())
                .isEqualTo(204);

        assertThat(call(get("/me"), phone, null).getResponse().getStatus()).isEqualTo(401);
        assertThat(call(get("/me"), laptop, null).getResponse().getStatus()).isEqualTo(401);
        assertThat(loginAndGetToken(login, PASSWORD)).isNull();
    }

    @Test
    void newestTokenSupersedesThePreviousOne() throws Exception {
        String login = unique("Новее");
        String email = unique("новее") + "@example.org";
        register(login, PASSWORD, email);

        requestReset(email, ip(7));
        String firstToken = tokenFromLastMail(email);
        requestReset(email, ip(7));
        String secondToken = tokenFromLastMail(email);
        assertThat(secondToken).isNotEqualTo(firstToken);

        var oldToken = call(post("/me/password/reset/confirm"), null,
                Map.of("token", firstToken, "newPassword", OTHER_PASSWORD));
        assertThat(oldToken.getResponse().getStatus())
                .as("прежняя ссылка гасится при выдаче новой")
                .isEqualTo(400);

        var newToken = call(post("/me/password/reset/confirm"), null,
                Map.of("token", secondToken, "newPassword", OTHER_PASSWORD));
        assertThat(newToken.getResponse().getStatus()).isEqualTo(204);
    }

    @Test
    void repeatedRequestsForTheSameAddressAreRateLimited() throws Exception {
        String email = unique("лимит") + "@example.org";
        register(unique("Лимит"), PASSWORD, email);
        String clientIp = ip(8);

        for (int attempt = 0; attempt < 3; attempt++) {
            requestReset(email, clientIp);
        }

        var limited = mvc.perform(post("/me/password/reset")
                        .with(request -> {
                            request.setRemoteAddr(clientIp);
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email))))
                .andReturn();

        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
        assertThat(limited.getResponse().getContentAsString()).contains("rate_limited");
        assertThat(mails.to(email)).as("письма сверх лимита не отправляются").hasSize(3);
    }

    @Test
    void shortNewPasswordIsRejected() throws Exception {
        String login = unique("Короткий");
        String email = unique("короткий") + "@example.org";
        register(login, PASSWORD, email);
        requestReset(email, ip(9));
        String token = tokenFromLastMail(email);

        var tooShort = call(post("/me/password/reset/confirm"), null,
                Map.of("token", token, "newPassword", "1234567"));

        assertThat(tooShort.getResponse().getStatus()).isEqualTo(400);
        assertThat(bodyOf(tooShort)).contains("invalid_password");
    }

    @Test
    void unknownTokenIsRejected() throws Exception {
        var unknown = call(post("/me/password/reset/confirm"), null,
                Map.of("token", "не-существующий-токен", "newPassword", OTHER_PASSWORD));

        assertThat(unknown.getResponse().getStatus()).isEqualTo(400);
    }
}
