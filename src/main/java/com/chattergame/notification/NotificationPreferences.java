package com.chattergame.notification;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * Настройка уведомлений: карта «событие → канал» целиком (`PLAN` O03, `D-148`).
 *
 * <p>Ответ всегда содержит все пять событий. Строки в базе появляются только после того,
 * как человек что-то переключил, поэтому «нет строки» означает «канал по умолчанию», а не
 * «событие забыто». Читатель, которому нужна полная карта, не должен знать, что часть
 * значений пришла из кода.
 */
@Service
public class NotificationPreferences {

    private final NotificationPreferenceRepository repository;

    public NotificationPreferences(NotificationPreferenceRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Map<NotificationEvent, NotificationChannel> of(UUID userId) {
        Map<NotificationEvent, NotificationChannel> map = new EnumMap<>(NotificationEvent.class);
        for (NotificationEvent event : NotificationEvent.values()) {
            map.put(event, event.defaultChannel());
        }
        for (NotificationPreferenceRepository.Row row : repository.findByUser(userId)) {
            NotificationEvent event = eventOf(row.eventType());
            // Неизвестное событие — строка от другой версии приложения: пропускаем, а не
            // падаем на чтении собственной настройки.
            if (event != null) {
                map.put(event, channelOf(row.channel(), event.defaultChannel()));
            }
        }
        return map;
    }

    /** Канал одного события. Отдельный метод, потому что рассылка спрашивает по одному. */
    @Transactional(readOnly = true)
    public NotificationChannel channelFor(UUID userId, NotificationEvent event) {
        return of(userId).getOrDefault(event, event.defaultChannel());
    }

    /**
     * Частичное обновление: переключатель меняет одно событие, и присылать всю карту
     * ради этого значило бы перезаписать чужие изменения, сделанные с другого устройства.
     */
    @Transactional
    public Map<NotificationEvent, NotificationChannel> update(
            UUID userId, Map<NotificationEvent, NotificationChannel> changes) {
        changes.forEach((event, channel) -> repository.upsert(userId, event, channel));
        return of(userId);
    }

    public static NotificationEvent eventOf(String value) {
        for (NotificationEvent event : NotificationEvent.values()) {
            if (event.name().equals(value)) {
                return event;
            }
        }
        return null;
    }

    public static NotificationChannel channelOf(String value, NotificationChannel fallback) {
        for (NotificationChannel channel : NotificationChannel.values()) {
            if (channel.name().equals(value)) {
                return channel;
            }
        }
        return fallback;
    }
}
