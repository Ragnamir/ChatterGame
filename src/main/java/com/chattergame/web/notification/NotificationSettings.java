package com.chattergame.web.notification;

import com.chattergame.notification.NotificationChannel;
import com.chattergame.notification.NotificationEvent;
import com.chattergame.notification.NotificationPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Карта «событие → канал» в ответе API (`PLAN` O03, `D-148`).
 *
 * <p>Список, а не объект: порядок задан словарём событий, и клиент рисует настройку в том же
 * порядке, не сортируя её у себя. Ключ текста отдаётся вместе с каналом, чтобы фронт не
 * держал вторую копию соответствия «событие → строка» (`D-081`).
 */
public record NotificationSettings(List<Entry> events) {

    public static NotificationSettings of(Map<NotificationEvent, NotificationChannel> channels) {
        List<Entry> entries = new ArrayList<>(channels.size());
        for (NotificationEvent event : NotificationEvent.values()) {
            entries.add(new Entry(
                    event.name(),
                    channels.getOrDefault(event, event.defaultChannel()).name(),
                    event.textKey()));
        }
        return new NotificationSettings(List.copyOf(entries));
    }

    public record Entry(String event, String channel, String textKey) {
    }
}
