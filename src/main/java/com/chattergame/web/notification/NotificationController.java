package com.chattergame.web.notification;

import com.chattergame.notification.NotificationChannel;
import com.chattergame.notification.NotificationEvent;
import com.chattergame.notification.NotificationPreferences;
import com.chattergame.util.ApiException;
import com.chattergame.web.AuthContext;
import com.chattergame.web.AuthContextResolver;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.EnumMap;
import java.util.Map;

/**
 * Настройка уведомлений (`PLAN` O03, `D-148`).
 *
 * <p>Маршрут открыт всегда, хотя отправлять пока нечего: карта «событие → канал» — часть
 * профиля (`REQUIREMENTS §5`), и человек вправе расставить её заранее. Когда появится
 * транспорт, менять контракт не придётся.
 */
@RestController
@RequestMapping("/me/notifications")
public class NotificationController {

    private final NotificationPreferences preferences;

    public NotificationController(NotificationPreferences preferences) {
        this.preferences = preferences;
    }

    @GetMapping
    public NotificationSettings read(AuthContext context) {
        return NotificationSettings.of(
                preferences.of(AuthContextResolver.require(context).user().id()));
    }

    /**
     * Частичное обновление: тело — объект «событие → канал», названные события меняются,
     * остальные остаются как были. Неизвестное имя отклоняется, а не игнорируется: иначе
     * опечатка в ключе выглядела бы как сохранённая настройка.
     */
    @PatchMapping
    public NotificationSettings update(
            @RequestBody Map<String, String> changes, AuthContext context) {
        Map<NotificationEvent, NotificationChannel> parsed = new EnumMap<>(NotificationEvent.class);
        changes.forEach((name, channelName) -> {
            NotificationEvent event = NotificationPreferences.eventOf(name);
            if (event == null) {
                throw ApiException.badRequest(
                        "notification_event_unknown", "Неизвестное событие уведомления");
            }
            parsed.put(event, channelOf(channelName));
        });
        return NotificationSettings.of(
                preferences.update(AuthContextResolver.require(context).user().id(), parsed));
    }

    private static NotificationChannel channelOf(String value) {
        for (NotificationChannel channel : NotificationChannel.values()) {
            if (channel.name().equals(value)) {
                return channel;
            }
        }
        throw ApiException.badRequest("notification_channel_unknown", "Неизвестный канал уведомления");
    }
}
