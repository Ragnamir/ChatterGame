package com.chattergame.message;

import com.chattergame.journal.GameEventType;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Проекция журнала в системные сообщения (`PLAN` E08, задача 4, `D-031`).
 *
 * <p>`SYSTEM` — не запись в `message`, а чтение журнала: вход и выход, правки, переходы,
 * выдача предмета, запрет и разрешение хода не пишет никто, их порождает система. Хранить
 * их второй строкой значило бы держать две правды об одном событии (`D-033`), поэтому
 * в `inbox_entry` лежит только номер события, а тело собирается из `payload` на чтении.
 *
 * <p>Словарь типов здесь закрыт и совпадает с {@link GameEventType}: тип, не названный
 * проекцией, в ленту чата не попадает вовсе — иначе в неё просочилась бы служебная запись
 * вроде выдачи номера или правки названия игры.
 */
public final class SystemProjection {

    /**
     * Тип события → код проекции. Код стабилен и переводится клиентом по ключу
     * интернализации: текст сообщения не является контрактом (`D-081`).
     */
    private static final Map<GameEventType, String> CODES = new EnumMap<>(GameEventType.class);

    static {
        CODES.put(GameEventType.CHARACTER_ENTERED, "character_entered");
        CODES.put(GameEventType.CHARACTER_LEFT, "character_left");
        CODES.put(GameEventType.CHARACTER_APPEARED_LEFT, "character_appeared_left");
        CODES.put(GameEventType.CHARACTER_STEALTHED, "character_stealthed");
        CODES.put(GameEventType.CHARACTER_REVEALED, "character_revealed");
        CODES.put(GameEventType.CHARACTER_CREATED, "character_created");
        CODES.put(GameEventType.CHARACTER_CONTENT_CHANGED, "character_content_changed");
        CODES.put(GameEventType.CHARACTER_ARCHIVED, "character_archived");
        CODES.put(GameEventType.CHAT_CREATED, "chat_created");
        CODES.put(GameEventType.CHAT_CONTENT_CHANGED, "chat_content_changed");
        CODES.put(GameEventType.CHAT_ARCHIVED, "chat_archived");
        CODES.put(GameEventType.CHAT_ACCESS_CHANGED, "chat_access_changed");
        CODES.put(GameEventType.CHAT_MEMBER_INVITED, "chat_member_invited");
        CODES.put(GameEventType.CHAT_MEMBER_JOINED, "chat_member_joined");
        CODES.put(GameEventType.CHAT_MEMBER_DECLINED, "chat_member_declined");
        CODES.put(GameEventType.CHAT_MEMBER_LEFT, "chat_member_left");
        CODES.put(GameEventType.TRANSITION_OPENED, "transition_opened");
        CODES.put(GameEventType.TRANSITION_CLOSED, "transition_closed");
        CODES.put(GameEventType.TRANSITION_ARCHIVED, "transition_archived");
        CODES.put(GameEventType.TRANSITION_STATE_CHANGED, "transition_state_changed");
        CODES.put(GameEventType.STORAGE_CREATED, "storage_created");
        CODES.put(GameEventType.STORAGE_CONTENT_CHANGED, "storage_content_changed");
        CODES.put(GameEventType.STORAGE_ACCESS_CHANGED, "storage_opened");
        CODES.put(GameEventType.ITEM_CREATED, "item_created");
        CODES.put(GameEventType.ITEM_CONTENT_CHANGED, "item_content_changed");
        CODES.put(GameEventType.ITEM_MOVED, "item_moved");
        CODES.put(GameEventType.RESTRICTION_SET, "restriction_set");
        CODES.put(GameEventType.RESTRICTION_REVOKED, "restriction_revoked");
        CODES.put(GameEventType.TURN_OPENED, "turn_opened");
        CODES.put(GameEventType.TURN_CLOSED, "turn_closed");
        CODES.put(GameEventType.CHARACTER_OCCUPIED, "character_occupied");
        CODES.put(GameEventType.CHARACTER_RELEASED, "character_released");
        CODES.put(GameEventType.GAME_STARTED, "game_started");
        CODES.put(GameEventType.GAME_FINISHED, "game_finished");
        CODES.put(GameEventType.GAME_PAUSED, "game_paused");
        CODES.put(GameEventType.GAME_RESUMED, "game_resumed");
    }

    private SystemProjection() {
    }

    public static boolean projects(GameEventType type) {
        return CODES.containsKey(type);
    }

    public static String codeOf(GameEventType type) {
        String code = CODES.get(type);
        if (code == null) {
            throw new IllegalArgumentException("Тип не проецируется в системное сообщение: " + type);
        }
        return code;
    }

    /** Чат события. Берётся из `payload`, потому что колонка `game_event.chat_id` уже занята. */
    public static Optional<UUID> chatIdOf(Map<String, Object> payload) {
        Object value = payload == null ? null : payload.get("chatId");
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value.toString()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
