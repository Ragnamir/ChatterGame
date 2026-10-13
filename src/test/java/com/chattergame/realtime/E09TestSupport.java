package com.chattergame.realtime;

import com.chattergame.user.Logins;
import com.chattergame.user.SessionCookie;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Обвязка тестов `E09`: живой HTTP-сервер на случайном порту и настоящие сокеты.
 *
 * <p>`MockMvc` здесь не годится: handshake, `Origin` и разрыв соединения — предмет этапа,
 * а подделанный транспорт их не проверяет вовсе. Поэтому приложение поднимается целиком,
 * а клиент подключается с cookie, как это сделал бы браузер.
 *
 * <p>Ожидание события всегда с таймаутом: висящий тест вместо падения означал бы, что
 * доставка «иногда работает», и это худший вид зелёного.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
abstract class E09TestSupport {

    protected static final String PASSWORD = "пароль-для-теста-42";

    /** Один из разрешённых `Origin` из `application.yaml` (`D-073`). */
    protected static final String ORIGIN = "http://localhost:5173";

    /** Чужой домен: cookie уходит, но сокет обязан быть отвергнут (`D-073`). */
    protected static final String FOREIGN_ORIGIN = "http://evil.example";

    private static final Duration AWAIT = Duration.ofSeconds(10);

    @LocalServerPort
    protected int port;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected ObjectMapper json;

    @Autowired
    protected RealtimeSessions sessions;

    @Autowired
    protected RealtimeProperties properties;

    private final HttpClient http = HttpClient.newHttpClient();

    // --- HTTP -------------------------------------------------------------------------

    protected record Response(int status, JsonNode body, String cookie) {

        protected String field(String name) {
            return body == null || body.get(name) == null ? null : body.get(name).asText();
        }
    }

    protected String baseUrl() {
        return "http://localhost:" + port;
    }

    protected String socketUrl(UUID gameId) {
        return "ws://localhost:" + port + "/games/" + gameId + "/ws";
    }

    protected Response call(String method, String path, String token, Map<String, ?> payload) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl() + path));
        if (token != null) {
            request.header(HttpHeaders.COOKIE, SessionCookie.NAME + "=" + token);
        }
        if (payload == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(write(payload), StandardCharsets.UTF_8));
        }
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            String body = response.body();
            return new Response(
                    response.statusCode(),
                    body == null || body.isBlank() ? null : json.readTree(body),
                    cookieOf(response));
        } catch (Exception e) {
            throw new IllegalStateException("HTTP-вызов не выполнен: " + method + " " + path, e);
        }
    }

    protected Response post(String path, String token, Map<String, ?> payload) {
        return call("POST", path, token, payload);
    }

    protected Response put(String path, String token, Map<String, ?> payload) {
        return call("PUT", path, token, payload);
    }

    protected Response get(String path, String token) {
        return call("GET", path, token, null);
    }

    protected String registerAndGetToken(String login) {
        Response response = post("/auth/register", null, Map.of("login", login, "password", PASSWORD));
        assertThat(response.status())
                .as("регистрация обязана пройти: тело ответа %s", response.body())
                .isEqualTo(201);
        assertThat(response.cookie()).as("сессия выдаётся cookie").isNotNull();
        return response.cookie();
    }

    protected UUID userId(String login) {
        return jdbc.queryForObject(
                "SELECT id FROM app_user WHERE login_normalized = ?", UUID.class, Logins.normalize(login));
    }

    protected static String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8);
    }

    private String write(Map<String, ?> payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Тело запроса не сериализуется", e);
        }
    }

    private static String cookieOf(HttpResponse<String> response) {
        for (String value : response.headers().allValues(HttpHeaders.SET_COOKIE)) {
            if (value.startsWith(SessionCookie.NAME + "=")) {
                int end = value.indexOf(';');
                String pair = end < 0 ? value : value.substring(0, end);
                return pair.substring(SessionCookie.NAME.length() + 1);
            }
        }
        return null;
    }

    // --- Игровой мир ------------------------------------------------------------------

    /**
     * Игра в фазе `RUNNING` с одной локацией и двумя персонажами: мастер и игрок. Мир
     * строится через HTTP, потому что сокет обязан работать поверх обычных ручек, а не
     * вместо них.
     */
    protected Fixture runningGame() {
        String masterLogin = unique("Мастер");
        String masterToken = registerAndGetToken(masterLogin);
        UUID masterUserId = userId(masterLogin);
        Response created = post("/games", masterToken, Map.of("title", "Игра " + unique("E09")));
        assertThat(created.status()).isEqualTo(201);
        UUID gameId = UUID.fromString(created.field("id"));

        Response location = post("/games/" + gameId + "/locations", masterToken,
                Map.of("title", "Маячная башня"));
        assertThat(location.status()).isEqualTo(201);
        UUID locationId = UUID.fromString(location.field("id"));

        UUID masterCharacter = createCharacter(masterToken, gameId, "Смотритель");
        place(masterToken, gameId, masterCharacter, locationId);

        String playerLogin = unique("Игрок");
        String playerToken = registerAndGetToken(playerLogin);
        UUID playerUserId = userId(playerLogin);
        jdbc.update(
                "INSERT INTO membership (id, game_id, user_id, role, status, created_at, joined_at) "
                        + "VALUES (?, ?, ?, 'PLAYER', 'JOINED', now(), now())",
                UUID.randomUUID(), gameId, playerUserId);
        UUID playerMembership = jdbc.queryForObject(
                "SELECT id FROM membership WHERE game_id = ? AND user_id = ?",
                UUID.class, gameId, playerUserId);
        UUID playerCharacter = createCharacter(masterToken, gameId, "Наблюдатель");
        place(masterToken, gameId, playerCharacter, locationId);
        jdbc.update("UPDATE character SET occupant_membership_id = ? WHERE id = ?",
                playerMembership, playerCharacter);

        assertThat(post("/games/" + gameId + "/transitions", masterToken,
                Map.of("transition", "MARK_READY")).status()).isEqualTo(200);
        assertThat(post("/games/" + gameId + "/transitions", masterToken,
                Map.of("transition", "START")).status()).isEqualTo(200);

        return new Fixture(gameId, locationId, masterUserId, masterToken,
                masterCharacter, playerUserId, playerToken, playerCharacter, playerMembership);
    }

    protected record Fixture(
            UUID gameId,
            UUID locationId,
            UUID masterUserId,
            String masterToken,
            UUID masterCharacter,
            UUID playerUserId,
            String playerToken,
            UUID playerCharacter,
            UUID playerMembership) {
    }

    private UUID createCharacter(String token, UUID gameId, String name) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("name", name + "-" + unique(""));
        card.put("playable", true);
        card.put("npc", false);
        Response response = post("/games/" + gameId + "/characters", token, card);
        assertThat(response.status()).as("карточка создаётся: %s", response.body()).isEqualTo(201);
        return UUID.fromString(response.field("id"));
    }

    private void place(String token, UUID gameId, UUID characterId, UUID chatId) {
        Response response = put("/games/" + gameId + "/characters/" + characterId + "/location",
                token, Map.of("chatId", chatId.toString()));
        assertThat(response.status()).as("персонаж размещён: %s", response.body()).isEqualTo(200);
    }

    /** Отправка сообщения обычным HTTP-путём: сокет и REST обязаны видеть одно и то же. */
    protected Response postMessage(Fixture fixture, String token, UUID characterId, String body) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "SPEECH");
        payload.put("body", body);
        if (characterId != null) {
            payload.put("characterId", characterId.toString());
        }
        return post("/games/" + fixture.gameId() + "/chats/" + fixture.locationId() + "/messages",
                token, payload);
    }

    // --- Сокет ------------------------------------------------------------------------

    /** Ожидание условия в реальном времени: сокет-события приходят асинхронно. */
    protected void awaitTrue(String what, java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("Условие не наступило: " + what);
    }

    /** Подключение: cookie сессии в заголовке и `Origin` — как у браузера (`D-073`). */
    protected Socket open(UUID gameId, String token, String origin) {
        return Socket.connect(json, socketUrl(gameId), token, origin);
    }

    /**
     * Клиент сокета в тестах. Складывает входящие события в очередь: тест забирает их
     * предикатом и падает по таймауту, а не ждёт вечно.
     */
    protected static final class Socket extends TextWebSocketHandler implements AutoCloseable {

        private final ObjectMapper json;
        private final BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
        private final List<JsonNode> skipped = new ArrayList<>();
        private WebSocketSession session;

        private Socket(ObjectMapper json) {
            this.json = json;
        }

        static Socket connect(ObjectMapper json, String url, String token, String origin) {
            Socket socket = new Socket(json);
            try {
                CompletableFuture<WebSocketSession> future = new StandardWebSocketClient()
                        .execute(socket, headers(token, origin), URI.create(url));
                socket.session = future.get(AWAIT.toSeconds(), TimeUnit.SECONDS);
                return socket;
            } catch (Exception e) {
                throw new IllegalStateException("Сокет не открылся: " + url, e);
            }
        }

        /** Подключение, которое обязано быть отвергнуто: возвращает причину отказа. */
        static Throwable refused(String url, String token, String origin) {
            Socket socket = new Socket(new ObjectMapper());
            try {
                new StandardWebSocketClient().execute(socket, headers(token, origin), URI.create(url))
                        .get(AWAIT.toSeconds(), TimeUnit.SECONDS);
                return null;
            } catch (Exception e) {
                return e;
            }
        }

        private static WebSocketHttpHeaders headers(String token, String origin) {
            WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
            if (token != null) {
                headers.add(HttpHeaders.COOKIE, SessionCookie.NAME + "=" + token);
            }
            if (origin != null) {
                headers.add(HttpHeaders.ORIGIN, origin);
            }
            return headers;
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            try {
                received.add(json.readTree(message.getPayload()));
            } catch (Exception e) {
                throw new IllegalStateException("Событие сокета не разобрано", e);
            }
        }

        void send(Map<String, Object> payload) {
            try {
                session.sendMessage(new TextMessage(json.writeValueAsString(payload)));
            } catch (Exception e) {
                throw new IllegalStateException("Событие не отправлено в сокет", e);
            }
        }

        void subscribe(UUID chatId, UUID asCharacter, boolean bodies) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "subscribe");
            payload.put("chatId", chatId.toString());
            if (asCharacter != null) {
                payload.put("asCharacter", asCharacter.toString());
            }
            payload.put("bodies", bodies);
            send(payload);
        }

        void post(UUID chatId, UUID asCharacter, String body) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "post");
            payload.put("chatId", chatId.toString());
            payload.put("kind", "SPEECH");
            payload.put("body", body);
            if (asCharacter != null) {
                payload.put("asCharacter", asCharacter.toString());
            }
            send(payload);
        }

        void resume(long lastSeq) {
            send(Map.of("type", "resume", "lastSeq", lastSeq));
        }

        void ping() {
            send(Map.of("type", "ping"));
        }

        /** Первое событие, удовлетворяющее предикату; таймаут — падение, а не пустой ответ. */
        JsonNode await(Predicate<JsonNode> predicate) {
            long deadline = System.nanoTime() + AWAIT.toNanos();
            while (System.nanoTime() < deadline) {
                JsonNode event = poll(deadline);
                if (event == null) {
                    break;
                }
                if (predicate.test(event)) {
                    return event;
                }
                // несовпавшее событие не выбрасывается: иначе ожидание служебного ответа
                // съедало бы доставленные строки и тест проверял бы пустоту
                skipped.add(event);
            }
            throw new AssertionError("Событие сокета не пришло за " + AWAIT.toSeconds() + " с");
        }

        JsonNode awaitChannel(String channel) {
            return await(event -> channel.equals(event.path("channel").asText()));
        }

        /** Все события, накопленные к моменту, когда очередь молчит `quietMillis`. */
        List<JsonNode> drain(long quietMillis) {
            List<JsonNode> events = new ArrayList<>(skipped);
            skipped.clear();
            while (true) {
                try {
                    JsonNode event = received.poll(quietMillis, TimeUnit.MILLISECONDS);
                    if (event == null) {
                        return events;
                    }
                    events.add(event);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return events;
                }
            }
        }

        private JsonNode poll(long deadlineNanos) {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                return null;
            }
            try {
                return received.poll(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        boolean closed() {
            return session == null || !session.isOpen();
        }

        @Override
        public void close() {
            try {
                if (session != null && session.isOpen()) {
                    session.close(CloseStatus.NORMAL);
                }
            } catch (Exception e) {
                // соединение уже закрыто — для теста это тот же результат
            }
        }
    }
}
