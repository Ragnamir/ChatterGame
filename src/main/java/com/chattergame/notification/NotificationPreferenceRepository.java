package com.chattergame.notification;

import com.chattergame.util.Uuids;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Доступ к `notification_preference` — карте «событие → канал» (`D-070`, `D-148`).
 *
 * <p>Запись — `upsert` по уникальному `(user_id, event_type)`: настройка меняется
 * переключателем, и «вторая строка на то же событие» сделала бы ответ зависящим от порядка
 * чтения. Отдельного `updated_at` нет — таблица заведена в базовой миграции, и менять схему
 * ради отметки, которую никто не читает, незачем.
 */
@Repository
public class NotificationPreferenceRepository {

    private final JdbcTemplate jdbc;

    public NotificationPreferenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Row> findByUser(UUID userId) {
        return jdbc.query(
                "SELECT event_type, channel FROM notification_preference WHERE user_id = ? "
                        + "ORDER BY event_type",
                (rs, rowNum) -> new Row(rs.getString("event_type"), rs.getString("channel")),
                userId);
    }

    public void upsert(UUID userId, NotificationEvent event, NotificationChannel channel) {
        jdbc.update(
                "INSERT INTO notification_preference (id, user_id, event_type, channel) "
                        + "VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT (user_id, event_type) DO UPDATE SET channel = EXCLUDED.channel",
                Uuids.v7(),
                userId,
                event.name(),
                channel.name());
    }

    /** Строка как она лежит в базе: разбор значений — дело сервиса, а не SQL. */
    public record Row(String eventType, String channel) {
    }
}
