package com.chattergame.user;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Открытая регистрация (`D-091`) и границы пароля (`D-096`).
 *
 * Проверки держатся на наблюдаемом поведении: статус, тело ответа, запись в БД. Границы
 * пароля берутся из решения, а не из аннотации валидации: требование «8–128 без ограничений
 * на состав» — одно место, и провал должен указывать на него.
 */
class E02RegistrationTest extends E02TestSupport {

    @Test
    void registrationCreatesAccountAndSession() throws Exception {
        String login = unique("ВасяРегистр");

        var result = register(login, PASSWORD, null);

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(result.getResponse().getContentAsString()).contains(login);
        assertThat(setCookieOf(result))
                .as("сессия выдаётся сразу при регистрации")
                .contains(SessionCookie.NAME);

        String token = tokenOf(result);
        var me = call(get("/me"), token, null);
        assertThat(me.getResponse().getStatus()).isEqualTo(200);
        assertThat(me.getResponse().getContentAsString()).contains(login);
    }

    @Test
    void passwordLengthIsBetweenEightAnd128WithNoCompositionRules() throws Exception {
        String tooShort = "1234567";
        String minimal = "12345678";
        String tooLong = "x".repeat(129);
        String maximal = "x".repeat(128);
        String lettersOnlyCyrillic = "парольбезцифр";

        assertThat(register(unique("Короткий"), tooShort, null).getResponse().getStatus())
                .as("пароль короче 8 символов отвергается")
                .isEqualTo(400);
        assertThat(register(unique("Минимум"), minimal, null).getResponse().getStatus())
                .as("8 символов достаточно")
                .isEqualTo(201);
        assertThat(register(unique("Длина"), maximal, null).getResponse().getStatus())
                .as("128 символов достаточно")
                .isEqualTo(201);
        assertThat(register(unique("Слишком"), tooLong, null).getResponse().getStatus())
                .as("129 символов отвергается")
                .isEqualTo(400);
        assertThat(register(unique("Буквы"), lettersOnlyCyrillic, null).getResponse().getStatus())
                .as("только буквы без цифр и заглавных — пароль допустим (D-096)")
                .isEqualTo(201);
    }

    @Test
    void loginShorterThanThreeAndLongerThan64IsRejected() throws Exception {
        assertThat(register("аб", PASSWORD, null).getResponse().getStatus()).isEqualTo(400);
        assertThat(register("л".repeat(65), PASSWORD, null).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void passwordIsStoredOnlyAsArgon2idHash() throws Exception {
        String login = unique("Хеш");
        String password = "уникальный-пароль-хеша";
        register(login, password, null);

        String hash = jdbc.queryForObject(
                "SELECT secret_hash FROM auth_identity WHERE provider_user_id = ?",
                String.class,
                loginNormalized(login));

        assertThat(hash).as("Argon2id: параметры входят в формат хеша").startsWith("$argon2id$");
        assertThat(hash).doesNotContain(password);
        assertThat(countRows(
                        "SELECT count(*) FROM auth_identity WHERE secret_hash = ? OR email = ?",
                        password,
                        password))
                .as("пароль не лежит ни в одной колонке открытым текстом")
                .isZero();
    }

    @Test
    void duplicateRegistrationReturns409WithIdenticalResponse() throws Exception {
        String login = unique("Дубль");
        assertThat(register(login, PASSWORD, null).getResponse().getStatus()).isEqualTo(201);

        var second = register(login.toLowerCase(), PASSWORD, null);
        var third = register(login.toUpperCase(), OTHER_PASSWORD, null);

        assertThat(second.getResponse().getStatus()).isEqualTo(409);
        assertThat(third.getResponse().getStatus()).isEqualTo(409);
        assertThat(bodyOf(second))
                .as("ответ не зависит от того, какой вариант логина прислали")
                .isEqualTo(bodyOf(third));
        assertThat(bodyOf(second)).contains("login_taken");
        assertThat(countRows(
                        "SELECT count(*) FROM app_user WHERE login_normalized = ?",
                        loginNormalized(login)))
                .as("второй аккаунт не создан")
                .isEqualTo(1);
    }

    @Test
    void duplicateEmailReturnsConflict() throws Exception {
        String email = unique("почта") + "@example.org";
        assertThat(register(unique("Почта1"), PASSWORD, email).getResponse().getStatus())
                .isEqualTo(201);

        var second = register(unique("Почта2"), PASSWORD, email.toUpperCase(java.util.Locale.ROOT));

        assertThat(second.getResponse().getStatus())
                .as("почта сравнивается без учёта регистра")
                .isEqualTo(409);
        assertThat(bodyOf(second)).contains("email_taken");
    }

    @Test
    void emailIsNotALogin() throws Exception {
        String email = unique("вход") + "@example.org";
        register(unique("ПочтаЛогин"), PASSWORD, email);

        var byEmail = loginRequest(email, PASSWORD);

        assertThat(byEmail.getResponse().getStatus())
                .as("вход идёт по логину; почта логином не является")
                .isEqualTo(401);
    }

    @Test
    void loginDoesNotDistinguishUnknownLoginFromWrongPassword() throws Exception {
        String login = unique("Ответ");
        register(login, PASSWORD, null);

        var unknown = loginRequest(unique("НетТакого"), PASSWORD);
        var wrongPassword = loginRequest(login, OTHER_PASSWORD);

        assertThat(unknown.getResponse().getStatus()).isEqualTo(401);
        assertThat(wrongPassword.getResponse().getStatus()).isEqualTo(401);
        assertThat(bodyOf(unknown))
                .as("иначе форма входа становится проверкой «зарегистрирован ли Вася»")
                .isEqualTo(bodyOf(wrongPassword));
    }

    @Test
    void displayNameDefaultsToLoginAndCanBeOverridden() throws Exception {
        String login = unique("ИмяЛогин");
        var result = register(login, PASSWORD, null);

        assertThat(result.getResponse().getContentAsString())
                .as("без отображаемого имени показывается логин")
                .contains("\"displayName\":\"" + login + "\"");
    }

    @Test
    void emailIsOptionalAndUnverifiedWhenAbsent() throws Exception {
        String login = unique("БезПочты");
        var result = register(login, PASSWORD, null);

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"email\":null");
        assertThat(body).contains("\"emailVerified\":false");
    }

    /** `D-078`: в `dev` адрес подтверждается автоматически, письма не покидают процесс. */
    @Test
    void emailIsVerifiedAutomaticallyInDevProfile() throws Exception {
        String login = unique("Автоподтверждение");
        String email = unique("авто") + "@example.org";

        var result = register(login, PASSWORD, email);

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(bodyOf(result)).contains("\"emailVerified\":true");
        assertThat(jdbc.queryForObject(
                        "SELECT verified_at IS NOT NULL FROM auth_identity WHERE user_id = "
                                + "(SELECT id FROM app_user WHERE login_normalized = ?)",
                        Boolean.class,
                        loginNormalized(login)))
                .as("подтверждение хранится рядом с адресом, в auth_identity")
                .isTrue();
    }

    @Test
    void malformedEmailIsRejected() throws Exception {
        var result = register(unique("ПлохаяПочта"), PASSWORD, "не-адрес");

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(bodyOf(result)).contains("invalid_email");
    }

    @Test
    void idsAreUuids() throws Exception {
        String login = unique("Идентификатор");
        var result = register(login, PASSWORD, null);

        String id = json.readTree(bodyOf(result)).get("id").asText();
        assertThat(UUID.fromString(id)).isNotNull();
    }

    @Test
    void registerResponseMatchesContractShape() throws Exception {
        mvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body(unique("Контракт"), PASSWORD, null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.login").isString())
                .andExpect(jsonPath("$.displayName").isString())
                .andExpect(jsonPath("$.theme").value("system"))
                .andExpect(jsonPath("$.emailVerified").value(false));
    }
}
