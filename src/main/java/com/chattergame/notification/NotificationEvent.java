package com.chattergame.notification;

/**
 * Пять событий, о которых платформа собирается уведомлять (`PLAN` O03, `D-148`).
 *
 * <p>Список закрыт решением, а не местом вызова: это словарь пользовательской настройки,
 * и значение живёт в `notification_preference.event_type`. Строка на месте вызова не
 * проверялась бы компилятором, а неизвестное значение в колонке сделало бы настройку
 * нечитаемой.
 *
 * <p>Текст уведомления — ключ интернализации (`D-081`), а не готовая фраза: сервер не знает
 * языка получателя и не должен склеивать текст из идентификаторов. Параметры ключа — только
 * идентификаторы игры, чата и события: имена персонажей, названия каналов и тела сообщений
 * в уведомление не попадают, иначе закрытая история утекла бы в лог и в чужой клиент
 * (`D-046`).
 */
public enum NotificationEvent {

    /** Приглашение в шёпот: адресат — приглашённый персонаж или участие. */
    WHISPER_INVITATION("notification.whisperInvitation", NotificationChannel.IN_APP),

    /** Ответ мастера в личном чате: адресат — игрок, а не автор. */
    MASTER_REPLY("notification.masterReply", NotificationChannel.IN_APP),

    /** Открытие хода: адресат — игроки, у которых есть персонажи. */
    TURN_OPENED("notification.turnOpened", NotificationChannel.IN_APP),

    /** Персонаж перестал скрываться: адресат — присутствующие, кроме него самого. */
    CHARACTER_REVEALED("notification.characterRevealed", NotificationChannel.NONE),

    /** Предмет оказался в чужом хранилище: адресат — владелец хранилища. */
    ITEM_OFFERED("notification.itemOffered", NotificationChannel.IN_APP);

    private final String textKey;
    private final NotificationChannel defaultChannel;

    NotificationEvent(String textKey, NotificationChannel defaultChannel) {
        this.textKey = textKey;
        this.defaultChannel = defaultChannel;
    }

    public String textKey() {
        return textKey;
    }

    /**
     * Канал, пока человек ничего не выбрал. Выход из скрытности по умолчанию молчит:
     * он случается в самой сцене, где адресат и так смотрит, а уведомление о нём —
     * шум, который выключают первым.
     */
    public NotificationChannel defaultChannel() {
        return defaultChannel;
    }
}
