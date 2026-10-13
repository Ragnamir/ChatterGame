package com.chattergame.realtime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Heartbeat и высвобождение мёртвых сокетов (`PLAN` E09, задача 6, `D-083`).
 *
 * <p>Живость определяет приложение, а не TCP: часть обрывов в мобильной сети до сервера
 * доходит по своему таймауту, который длиннее срока, когда доставка уже потеряла смысл.
 * Поэтому молчащий сокет закрывается по `idleTimeout`, а его подписки перестают участвовать
 * в рассылке.
 */
class E09HeartbeatTest extends E09TestSupport {

    @Autowired
    private RealtimeHeartbeat heartbeat;

    @Autowired
    private RealtimeEnvelope envelope;

    /** Живой сокет получает heartbeat: по нему клиент отличает сервера от молчащего посредника. */
    @Test
    void liveSocketReceivesHeartbeat() {
        Fixture fixture = runningGame();
        try (Socket player = open(fixture.gameId(), fixture.playerToken(), ORIGIN)) {
            player.awaitChannel("READY");
            heartbeat.beat();
            assertThat(player.awaitChannel("HEARTBEAT")).isNotNull();
        }
    }

    /**
     * Сокет, молчащий дольше таймаута, закрывается и высвобождается. Проверка идёт на
     * настоящем `RealtimeSession` и настоящем простое: таймаут берётся коротким, потому что
     * ждать минуту в тесте нельзя, а решает здесь именно сравнение простоя с таймаутом.
     */
    @Test
    void silentSocketIsClosedAndReleased() throws Exception {
        Fixture fixture = runningGame();
        try (Socket player = open(fixture.gameId(), fixture.playerToken(), ORIGIN)) {
            player.awaitChannel("READY");
            RealtimeSession live = sessionOf(fixture);
            RealtimeSessions registry = new RealtimeSessions();
            registry.add(live);

            Thread.sleep(300);

            new RealtimeHeartbeat(registry, envelope, properties(Duration.ofMillis(100))).beat();

            assertThat(registry.size()).as("молчащий сокет высвобожден из реестра").isZero();
            awaitTrue("сервер закрыл сокет по таймауту", player::closed);
        }
    }

    /** Закрытое соединение не копит подписки: рассылка иначе растёт с числом переподключений. */
    @Test
    void closedSocketIsReleasedFromTheRegistry() {
        Fixture fixture = runningGame();
        Socket player = open(fixture.gameId(), fixture.playerToken(), ORIGIN);
        player.awaitChannel("READY");
        int before = sessions.forGame(fixture.gameId()).size();
        String sessionId = sessionOf(fixture).id();

        player.close();

        awaitTrue("сервер заметил закрытие", () -> sessions.forGame(fixture.gameId()).size() < before);
        assertThat(sessions.byId(sessionId)).isNull();
    }

    /** Живой сокет не закрывается по таймауту: проверяется не факт закрытия, а простой. */
    @Test
    void activeSocketSurvivesHeartbeat() {
        Fixture fixture = runningGame();
        try (Socket player = open(fixture.gameId(), fixture.playerToken(), ORIGIN)) {
            player.awaitChannel("READY");
            RealtimeSession live = sessionOf(fixture);
            RealtimeSessions registry = new RealtimeSessions();
            registry.add(live);

            new RealtimeHeartbeat(registry, envelope, properties(Duration.ofMinutes(5))).beat();

            assertThat(registry.size()).isEqualTo(1);
            assertThat(player.awaitChannel("HEARTBEAT")).isNotNull();
        }
    }

    private RealtimeSession sessionOf(Fixture fixture) {
        return sessions.forGame(fixture.gameId()).stream()
                .filter(session -> session.userId().equals(fixture.playerUserId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Сокет игрока не зарегистрирован"));
    }

    private String sessionIdOf(Fixture fixture) {
        return sessionOf(fixture).id();
    }

    private static RealtimeProperties properties(Duration idleTimeout) {
        RealtimeProperties properties = new RealtimeProperties();
        properties.setIdleTimeout(idleTimeout);
        properties.setHeartbeatInterval(Duration.ofSeconds(30));
        return properties;
    }
}
