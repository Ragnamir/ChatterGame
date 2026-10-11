package com.chattergame.user;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Серверная сессия (`D-096`): 30 дней со скольжением, несколько устройств, выход,
 * «выйти на всех устройствах», инвалидация при смене пароля.
 *
 * В БД лежит SHA-256 от токена, а не токен: дамп базы не должен давать возможность
 * представиться чужой сессией. Это проверяется сравнением значения из cookie с колонкой.
 */
class E02SessionTest extends E02TestSupport {

    private static final String SET_COOKIE = "Set-Cookie";

    @Test
    void sessionCookieIsHttpOnlyLaxAndLongLived() throws Exception {
        var result = register(unique("Cookie"), PASSWORD, null);

        String header = result.getResponse().getHeader(SET_COOKIE);

        assertThat(header).as("cookie обязана быть HttpOnly (D-096)").contains("HttpOnly");
        assertThat(header).contains("SameSite=Lax");
        assertThat(header).contains("Path=/");
        assertThat(header)
                .as("30 дней в секундах")
                .contains("Max-Age=2592000");
        assertThat(header)
                .as("в dev приложение живёт на http, Secure сделал бы cookie неотправляемой")
                .doesNotContain("Secure");
    }

    @Test
    void databaseStoresOnlyHashOfSessionToken() throws Exception {
        String login = unique("ХешТокен");
        String token = registerAndGetToken(login, PASSWORD);

        String stored = jdbc.queryForObject(
                "SELECT token_hash FROM session WHERE user_id = "
                        + "(SELECT id FROM app_user WHERE login_normalized = ?)",
                String.class,
                loginNormalized(login));

        assertThat(stored).as("токен не хранится в открытом виде").isNotEqualTo(token);
        assertThat(stored)
                .as("ровно SHA-256 в шестнадцатеричном виде — ширина колонки 64")
                .isEqualTo(Tokens.hash(token));
        assertThat(countRows("SELECT count(*) FROM session WHERE token_hash = ?", token))
                .as("открытый токен в базе не встречается")
                .isZero();
    }

    @Test
    void sessionExpiresAfterThirtyDays() throws Exception {
        String login = unique("Срок");
        String token = registerAndGetToken(login, PASSWORD);

        Instant expiresAt = jdbc.queryForObject(
                "SELECT expires_at FROM session WHERE user_id = "
                        + "(SELECT id FROM app_user WHERE login_normalized = ?)",
                Instant.class,
                loginNormalized(login));

        assertThat(expiresAt)
                .isBetween(Instant.now().plusSeconds(29 * 24 * 3600), Instant.now().plusSeconds(31 * 24 * 3600));
        assertThat(call(get("/me"), token, null).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void expiredSessionIsRejected() throws Exception {
        String login = unique("Истёк");
        String token = registerAndGetToken(login, PASSWORD);
        jdbc.update(
                "UPDATE session SET expires_at = now() - interval '1 minute' WHERE user_id = "
                        + "(SELECT id FROM app_user WHERE login_normalized = ?)",
                loginNormalized(login));

        assertThat(call(get("/me"), token, null).getResponse().getStatus())
                .as("просроченная сессия не даёт доступ")
                .isEqualTo(401);
    }

    @Test
    void activitySlidesTheSessionExpiry() throws Exception {
        String login = unique("Скольжение");
        String token = registerAndGetToken(login, PASSWORD);
        jdbc.update(
                "UPDATE session SET last_seen_at = now() - interval '2 hours', "
                        + "expires_at = now() + interval '1 day' WHERE user_id = "
                        + "(SELECT id FROM app_user WHERE login_normalized = ?)",
                loginNormalized(login));

        assertThat(call(get("/me"), token, null).getResponse().getStatus()).isEqualTo(200);

        Instant expiresAt = jdbc.queryForObject(
                "SELECT expires_at FROM session WHERE user_id = "
                        + "(SELECT id FROM app_user WHERE login_normalized = ?)",
                Instant.class,
                loginNormalized(login));
        assertThat(expiresAt)
                .as("месяц игры не должен требовать повторного входа (D-096)")
                .isAfter(Instant.now().plusSeconds(29 * 24 * 3600));
    }

    @Test
    void severalDevicesAreAllowedAtOnce() throws Exception {
        String login = unique("Устройства");
        String phone = registerAndGetToken(login, PASSWORD);
        String laptop = loginAndGetToken(login, PASSWORD);

        assertThat(phone).isNotEqualTo(laptop);
        assertThat(call(get("/me"), phone, null).getResponse().getStatus()).isEqualTo(200);
        assertThat(call(get("/me"), laptop, null).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void logoutRevokesOnlyCurrentSession() throws Exception {
        String login = unique("Выход");
        String phone = registerAndGetToken(login, PASSWORD);
        String laptop = loginAndGetToken(login, PASSWORD);

        var logout = call(post("/auth/logout"), phone, null);

        assertThat(logout.getResponse().getStatus()).isEqualTo(204);
        assertThat(logout.getResponse().getHeader(SET_COOKIE))
                .as("cookie снимается тем же ответом")
                .contains("Max-Age=0");
        assertThat(call(get("/me"), phone, null).getResponse().getStatus()).isEqualTo(401);
        assertThat(call(get("/me"), laptop, null).getResponse().getStatus())
                .as("выход на одном устройстве не трогает остальные")
                .isEqualTo(200);
    }

    @Test
    void logoutAllRevokesEveryDevice() throws Exception {
        String login = unique("Везде");
        String phone = registerAndGetToken(login, PASSWORD);
        String laptop = loginAndGetToken(login, PASSWORD);

        assertThat(call(post("/auth/logout-all"), phone, null).getResponse().getStatus())
                .isEqualTo(204);

        assertThat(call(get("/me"), phone, null).getResponse().getStatus()).isEqualTo(401);
        assertThat(call(get("/me"), laptop, null).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void meRequiresSession() throws Exception {
        assertThat(call(get("/me"), null, null).getResponse().getStatus()).isEqualTo(401);
        assertThat(call(get("/me"), "подделка", null).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void changePasswordRevokesAllSessionsAndRotatesTheSecret() throws Exception {
        String login = unique("Смена");
        String phone = registerAndGetToken(login, PASSWORD);
        String laptop = loginAndGetToken(login, PASSWORD);

        var changed = call(
                post("/me/password"),
                phone,
                java.util.Map.of("currentPassword", PASSWORD, "newPassword", OTHER_PASSWORD));

        assertThat(changed.getResponse().getStatus()).isEqualTo(204);
        assertThat(changed.getResponse().getHeader(SET_COOKIE)).contains("Max-Age=0");
        assertThat(call(get("/me"), phone, null).getResponse().getStatus()).isEqualTo(401);
        assertThat(call(get("/me"), laptop, null).getResponse().getStatus())
                .as("смена пароля инвалидирует все сессии (D-096)")
                .isEqualTo(401);
        assertThat(loginRequest(login, PASSWORD).getResponse().getStatus())
                .as("старый пароль больше не работает")
                .isEqualTo(401);
        assertThat(loginAndGetToken(login, OTHER_PASSWORD)).isNotNull();
    }

    @Test
    void changePasswordRequiresCurrentPassword() throws Exception {
        String login = unique("Текущий");
        String token = registerAndGetToken(login, PASSWORD);

        var wrong = call(
                post("/me/password"),
                token,
                java.util.Map.of("currentPassword", OTHER_PASSWORD, "newPassword", "новый-пароль-88"));

        assertThat(wrong.getResponse().getStatus()).isEqualTo(401);
        assertThat(loginAndGetToken(login, PASSWORD))
                .as("пароль не изменился")
                .isNotNull();
    }

    @Test
    void changePasswordValidatesNewPasswordLength() throws Exception {
        String login = unique("Границы");
        String token = registerAndGetToken(login, PASSWORD);

        var tooShort = call(
                post("/me/password"),
                token,
                java.util.Map.of("currentPassword", PASSWORD, "newPassword", "1234567"));

        assertThat(tooShort.getResponse().getStatus()).isEqualTo(400);
        assertThat(bodyOf(tooShort)).contains("invalid_password");
    }
}
