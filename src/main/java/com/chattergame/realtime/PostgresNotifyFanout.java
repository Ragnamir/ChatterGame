package com.chattergame.realtime;

import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Фан-аут нескольких инстансов через `LISTEN/NOTIFY` (`D-084`). Уведомление несёт только
 * идентификатор события: тело читается из таблицы по номеру. `NOTIFY` ограничен 8 KB и
 * не даёт гарантий доставки, поэтому он триггер, а не транспорт содержимого.
 *
 * <p>Слушатель сидит на отдельном соединении: `LISTEN` привязан к соединению, и брать его
 * из пула значило бы держать пул занятым навсегда. Соединение переоткрывается при обрыве.
 */
public class PostgresNotifyFanout implements Fanout {

    /** Имя канала фиксировано: его же ищет `LISTEN`, и расхождение дало бы тихую потерю. */
    public static final String CHANNEL = "chattergame_events";

    private static final Logger log = LoggerFactory.getLogger(PostgresNotifyFanout.class);

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final CopyOnWriteArrayList<Consumer<GameEventRef>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread listener;

    public PostgresNotifyFanout(DataSource dataSource, JdbcTemplate jdbc) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
    }

    @Override
    public void publish(GameEventRef ref) {
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            try (var statement = connection.prepareStatement("SELECT pg_notify(?, ?)")) {
                statement.setString(1, CHANNEL);
                statement.setString(2, ref.toString());
                statement.execute();
            }
            return null;
        });
    }

    @Override
    public Subscription subscribe(Consumer<GameEventRef> listener) {
        ensureStarted();
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /** Ленивый старт: поток слушателя не поднимается, пока никто не подписался. */
    private synchronized void ensureStarted() {
        if (running.getAndSet(true)) {
            return;
        }
        listener = new Thread(this::listen, "chattergame-notify-listener");
        listener.setDaemon(true);
        listener.start();
    }

    private void listen() {
        while (running.get()) {
            try (Connection connection = dataSource.getConnection()) {
                PGConnection pg = connection.unwrap(PGConnection.class);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("LISTEN " + CHANNEL);
                }
                while (running.get()) {
                    PGNotification[] notifications = pg.getNotifications(1000);
                    if (notifications != null) {
                        for (PGNotification notification : notifications) {
                            deliver(notification.getParameter());
                        }
                    }
                }
            } catch (Exception e) {
                if (running.get()) {
                    log.warn("Слушатель NOTIFY переподключается: {}", e.toString());
                    sleep();
                }
            }
        }
    }

    private void deliver(String payload) {
        GameEventRef ref;
        try {
            ref = GameEventRef.parse(payload);
        } catch (RuntimeException e) {
            log.warn("Уведомление без разбираемой ссылки: {}", payload);
            return;
        }
        for (Consumer<GameEventRef> listener : listeners) {
            listener.accept(ref);
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Остановка слушателя: нужна тестам и корректному завершению приложения. */
    public void stop() {
        running.set(false);
        if (listener != null) {
            listener.interrupt();
        }
    }
}
