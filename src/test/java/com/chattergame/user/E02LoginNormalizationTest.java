package com.chattergame.user;

import org.junit.jupiter.api.Test;

import java.text.Normalizer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Нормализация логина (`D-080`): уникальность и сравнение — по NFC-нормализованному нижнему
 * регистру, посчитанному сервером. Иначе `Вася`, `вася` и `ВАСЯ` — три аккаунта, а на macOS
 * ввод даёт NFD и создаёт четвёртый.
 *
 * Отображаемая форма отдельно: пользователь должен видеть свой логин в набранном регистре.
 */
class E02LoginNormalizationTest extends E02TestSupport {

    @Test
    void cyrillicCaseVariantsShareOneAccount() throws Exception {
        String login = "Вася" + unique("Н");
        assertThat(register(login, PASSWORD, null).getResponse().getStatus()).isEqualTo(201);

        assertThat(register(login.toLowerCase(), PASSWORD, null).getResponse().getStatus())
                .as("нижний регистр — тот же аккаунт")
                .isEqualTo(409);
        assertThat(register(login.toUpperCase(), PASSWORD, null).getResponse().getStatus())
                .as("верхний регистр — тот же аккаунт")
                .isEqualTo(409);

        assertThat(countRows(
                        "SELECT count(*) FROM app_user WHERE login_normalized = ?",
                        loginNormalized(login)))
                .isEqualTo(1);
        assertThat(loginAndGetToken(login.toUpperCase(), PASSWORD))
                .as("вход работает в любом регистре")
                .isNotNull();
        assertThat(loginAndGetToken(login.toLowerCase(), PASSWORD)).isNotNull();
    }

    @Test
    void decomposedAndComposedLoginsAreTheSameAccount() throws Exception {
        String composed = Normalizer.normalize("йогурт" + unique("Ь"), Normalizer.Form.NFC);
        String decomposed = Normalizer.normalize(composed, Normalizer.Form.NFD);

        assertThat(decomposed)
                .as("проверка осмысленна только если формы действительно различаются посимвольно")
                .isNotEqualTo(composed);
        assertThat(register(composed, PASSWORD, null).getResponse().getStatus()).isEqualTo(201);

        assertThat(register(decomposed, PASSWORD, null).getResponse().getStatus())
                .as("NFD-ввод с macOS не создаёт второй аккаунт")
                .isEqualTo(409);
        assertThat(loginAndGetToken(decomposed, PASSWORD)).isNotNull();
    }

    @Test
    void surroundingWhitespaceIsTrimmed() throws Exception {
        String login = "Пробел" + unique("Ы");

        assertThat(register("   " + login + "  ", PASSWORD, null).getResponse().getStatus())
                .isEqualTo(201);

        assertThat(loginAndGetToken(login, PASSWORD)).isNotNull();
        assertThat(countRows("SELECT count(*) FROM app_user WHERE login = ?", login)).isEqualTo(1);
    }

    @Test
    void displayFormKeepsTheCaseTheUserTyped() throws Exception {
        String login = "ВаСя" + unique("Я");

        var result = register(login, PASSWORD, null);

        assertThat(json.readTree(bodyOf(result)).get("login").asText())
                .as("пользователь видит свой логин в набранном регистре")
                .isEqualTo(login);
        assertThat(jdbc.queryForObject(
                        "SELECT login FROM app_user WHERE login_normalized = ?",
                        String.class,
                        loginNormalized(login)))
                .isEqualTo(login);
    }

    @Test
    void controlCharactersInLoginAreRejected() throws Exception {
        var result = register("плохо\u0000й", PASSWORD, null);

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(bodyOf(result)).contains("invalid_login");
    }

    @Test
    void differentCyrillicLettersAreDifferentAccounts() throws Exception {
        String withShortAnd = "и" + unique("И");
        String withY = "й" + unique("Й");

        assertThat(register(withShortAnd, PASSWORD, null).getResponse().getStatus()).isEqualTo(201);
        assertThat(register(withY, PASSWORD, null).getResponse().getStatus())
                .as("нормализация не должна склеивать разные буквы")
                .isEqualTo(201);
    }
}
