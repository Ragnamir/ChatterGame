package com.chattergame.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Аутентификация и `Origin` сокета (`PLAN` E09, задача 1, `D-073`).
 *
 * <p>Сокет не подчиняется правилу одного происхождения сам по себе: без проверки `Origin`
 * чужая страница в браузере пользователя откроет соединение с его cookie. Поэтому отказ
 * чужому домену здесь проверяется на живом handshake, а не на вызове метода: именно
 * handshake решает, дойдёт ли соединение до обработчика.
 */
class E09HandshakeTest extends E09TestSupport {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private SessionCookieHandshakeInterceptor cookieHandshake;

    @Test
    void socketOpensWithSessionCookieAndAllowedOrigin() {
        Fixture fixture = runningGame();
        try (Socket player = open(fixture.gameId(), fixture.playerToken(), ORIGIN)) {
            JsonNode ready = player.awaitChannel("READY");
            assertThat(ready.get("gameId").asText()).isEqualTo(fixture.gameId().toString());
            assertThat(ready.get("userId").asText()).isEqualTo(fixture.playerUserId().toString());
        }
    }

    /** Чужой домен отвергается до обработчика: cookie уходит, но соединения не будет. */
    @Test
    void foreignOriginIsRefused() {
        Fixture fixture = runningGame();
        int before = sessions.forGame(fixture.gameId()).size();

        Throwable refusal = Socket.refused(socketUrl(fixture.gameId()), fixture.playerToken(), FOREIGN_ORIGIN);

        assertThat(refusal).as("handshake с чужого домена обязан быть отвергнут").isNotNull();
        assertThat(describe(refusal)).contains("403");
        assertThat(sessions.forGame(fixture.gameId()))
                .as("отвергнутый handshake не попадает в реестр сокетов")
                .hasSize(before);
    }

    /** Отсутствие `Origin` — тоже отказ: пустое значение не означает «любой домен». */
    @Test
    void missingOriginIsRefused() {
        Fixture fixture = runningGame();
        Throwable refusal = Socket.refused(socketUrl(fixture.gameId()), fixture.playerToken(), null);
        assertThat(refusal).isNotNull();
    }

    /** Без сессии сокет не открывается: анонимный сокет читал бы игру без участия. */
    @Test
    void socketWithoutSessionIsRefused() {
        Fixture fixture = runningGame();
        Throwable refusal = Socket.refused(socketUrl(fixture.gameId()), null, ORIGIN);

        assertThat(refusal).isNotNull();
        assertThat(describe(refusal)).contains("401");
    }

    /** Просроченный или выдуманный токен — тот же отказ, что и отсутствие cookie (`D-096`). */
    @Test
    void socketWithUnknownTokenIsRefused() {
        Fixture fixture = runningGame();
        Throwable refusal = Socket.refused(socketUrl(fixture.gameId()), UUID.randomUUID().toString(), ORIGIN);

        assertThat(refusal).isNotNull();
        assertThat(describe(refusal)).contains("401");
    }

    /** Сверка `Origin` точная: домен, лишь содержащий разрешённый как подстроку, не проходит. */
    @Test
    void originComparisonIsExactAndCaseInsensitive() {
        OriginAllowlist allowlist = new OriginAllowlist(properties(List.of("http://localhost:5173")));

        assertThat(allowlist.allows("http://localhost:5173")).isTrue();
        assertThat(allowlist.allows("HTTP://LOCALHOST:5173")).isTrue();
        assertThat(allowlist.allows("  http://localhost:5173  ")).isTrue();
        assertThat(allowlist.allows("http://localhost:5173.evil.example")).isFalse();
        assertThat(allowlist.allows("http://evil.example")).isFalse();
        assertThat(allowlist.allows("https://localhost:5173")).as("схема входит в сравнение").isFalse();
        assertThat(allowlist.allows(null)).isFalse();
        assertThat(allowlist.allows("  ")).isFalse();
    }

    /** Пустой список закрывает доступ: забытая конфигурация не должна открывать сокет всем. */
    @Test
    void emptyAllowlistAllowsNothing() {
        OriginAllowlist allowlist = new OriginAllowlist(properties(List.of()));

        assertThat(allowlist.allows("http://localhost:5173")).isFalse();
        assertThat(allowlist.allows("https://chattergame.example")).isFalse();
    }

    /**
     * Запасная аутентификация по тикету выключена (`PLAN` E09, задача 1): без свойства
     * `chattergame.realtime.ticket-auth` её боб в контексте отсутствует, и второй способ
     * входа не появляется сам собой.
     */
    @Test
    void ticketHandshakeIsOffByDefault() {
        assertThat(context.getBeanNamesForType(TicketHandshakeInterceptor.class))
                .as("тикет-аутентификация включается только свойством и по умолчанию выключена")
                .isEmpty();
    }

    /** Билет одноразовый и сгорает по сроку: второй обмен им не проходит. */
    @Test
    void ticketIsConsumedOnceAndExpires() {
        TicketHandshakeInterceptor.InMemoryTicketStore store =
                new TicketHandshakeInterceptor.InMemoryTicketStore();
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        TicketHandshakeInterceptor.Principal principal =
                new TicketHandshakeInterceptor.Principal(userId, sessionId);

        String ticket = store.issue(principal, 10_000);
        Optional<TicketHandshakeInterceptor.Principal> first = store.consume(ticket);
        assertThat(first).contains(principal);
        assertThat(store.consume(ticket)).as("второй обмен тем же билетом не проходит").isEmpty();

        String expired = store.issue(principal, -1);
        assertThat(store.consume(expired)).as("просроченный билет не проходит").isEmpty();
        assertThat(store.consume(null)).isEmpty();
    }

    /** Имя cookie сокета — то же, что у HTTP-сессии: сокет не заводит второй способ входа. */
    @Test
    void socketReusesTheSessionCookieName() {
        assertThat(SessionCookieHandshakeInterceptor.cookieName())
                .isEqualTo(com.chattergame.user.SessionCookie.NAME);
        assertThat(cookieHandshake).isNotNull();
    }

    private static RealtimeProperties properties(List<String> origins) {
        RealtimeProperties properties = new RealtimeProperties();
        properties.setAllowedOrigins(origins);
        return properties;
    }

    /** Сообщение об отказе handshake: клиент получает статус, вложенный в цепочку исключений. */
    private static String describe(Throwable failure) {
        StringBuilder text = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            text.append(current.getMessage()).append(' ');
        }
        return text.toString();
    }
}
