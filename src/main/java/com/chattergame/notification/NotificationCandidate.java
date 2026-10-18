package com.chattergame.notification;

import java.util.Map;
import java.util.UUID;

/**
 * Одно уведомление, собранное из события журнала (`D-148`).
 *
 * <p>`params` — только идентификаторы: клиент сам решает, что показать, и берёт имена
 * из данных, к которым у него есть право доступа. Так уведомление не становится вторым
 * путём чтения закрытого содержимого (`D-030`, `D-046`).
 */
public record NotificationCandidate(
        NotificationEvent event,
        UUID userId,
        Map<String, Object> params) {

    public String textKey() {
        return event.textKey();
    }
}
