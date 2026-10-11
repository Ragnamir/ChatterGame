package com.chattergame.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Общая обвязка тестов `E02`: живой Spring-контекст, `MockMvc` и доступ к БД.
 *
 * Тесты идут через HTTP-слой, а не через сервисы: так одновременно проверяются контракт
 * (статусы, cookie, тело ответа) и записи в БД. Логин и адрес почты уникальны в каждом
 * тесте, поэтому тесты не зависят от порядка выполнения и не чистят базу друг за другом.
 *
 * Токен передаётся явным аргументом: несколько устройств — штатный сценарий (`D-096`),
 * и скрытое «текущее» состояние сделало бы проверку двух сессий запутанной.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
public abstract class E02TestSupport {

    protected static final String PASSWORD = "пароль-для-теста-42";
    protected static final String OTHER_PASSWORD = "другой-пароль-для-теста-77";

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected ObjectMapper json;

    /**
     * Уникальный хвост для логина и адреса. Берётся случайная часть UUID, а не начало
     * времени: у UUIDv7 старшие 32 бита — это миллисекунды, и они меняются раз в минуту,
     * поэтому два прогона подряд дали бы одинаковые логины и тесты падали бы на себе же.
     */
    protected static String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8);
    }

    protected static Map<String, Object> body(String login, String password, String email) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("login", login);
        payload.put("password", password);
        if (email != null) {
            payload.put("email", email);
        }
        return payload;
    }

    /** Единственная точка выполнения запроса: cookie и JSON-тело добавляются здесь. */
    protected MvcResult call(MockHttpServletRequestBuilder request, String token, Map<String, ?> payload)
            throws Exception {
        if (token != null) {
            request.cookie(new Cookie(SessionCookie.NAME, token));
        }
        if (payload != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload));
        }
        return mvc.perform(request).andReturn();
    }

    protected MvcResult register(String login, String password, String email) throws Exception {
        return call(post("/auth/register"), null, body(login, password, email));
    }

    /** Регистрация и cookie сессии: большинству тестов нужен именно вход. */
    protected String registerAndGetToken(String login, String password) throws Exception {
        return tokenOf(register(login, password, null));
    }

    protected MvcResult loginRequest(String login, String password) throws Exception {
        return call(post("/auth/login"), null, Map.of("login", login, "password", password));
    }

    protected String loginAndGetToken(String login, String password) throws Exception {
        return tokenOf(loginRequest(login, password));
    }

    protected static String tokenOf(MvcResult result) {
        Cookie cookie = result.getResponse().getCookie(SessionCookie.NAME);
        return cookie == null ? null : cookie.getValue();
    }

    protected static String setCookieOf(MvcResult result) {
        return result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
    }

    protected static String bodyOf(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    protected long countRows(String sql, Object... args) {
        Long count = jdbc.queryForObject(sql, Long.class, args);
        return count == null ? 0L : count;
    }

    protected String loginNormalized(String login) {
        return Logins.normalize(login);
    }
}
