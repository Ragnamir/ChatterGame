package com.chattergame.request;

import java.util.Optional;

/**
 * Операции change set (`REQUIREMENTS §17`, `D-041`). Словарь фиксируется целиком: имя
 * операции — часть контракта, потому что payload приходит извне (от модели), и переименование
 * ломало бы уже сохранённые заявки.
 *
 * <p>`reversible` — не украшение, а свойство операции: откат `D-041` восстанавливает старое
 * значение, а у создания сущности старого значения нет. Удаление запрещено (`D-010`), поэтому
 * «откатить» создание предмета или мастера-сообщения нельзя, и попытка обязана быть видимой
 * ошибкой, а не тихим пропуском.
 */
public enum ChangeOperationType {

    /** Правка одного поля персонажа: внешность или история (`§17`). Поле берётся списком. */
    UPDATE_CHARACTER_FIELD("update_character_field", true),

    /** Создание предмета в хранилище. Откат невозможен: удаление предмета запрещено (`D-010`). */
    CREATE_ITEM("create_item", false),

    /** Перемещение персонажа по переходу. Откат — возврат в прежнюю локацию. */
    MOVE_CHARACTER("move_character", true),

    /** Сообщение мастера в чат. Откат невозможен: отзыв сообщений запрещён (`D-008`). */
    POST_MASTER_MESSAGE("post_master_message", false),

    /** Правка названия, описания и открытости хранилища. Откат возвращает прежние значения. */
    UPDATE_STORAGE_CONTENTS("update_storage_contents", true),

    /** Заметка в тетради. Откат невозможен: `create_note` только заводит строку (`D-055`). */
    CREATE_NOTE("create_note", false);

    private final String wireName;
    private final boolean reversible;

    ChangeOperationType(String wireName, boolean reversible) {
        this.wireName = wireName;
        this.reversible = reversible;
    }

    /** Имя в payload, а не `name()`: значение приходит извне и не обязано совпадать с кодом. */
    public String wireName() {
        return wireName;
    }

    public boolean reversible() {
        return reversible;
    }

    public static Optional<ChangeOperationType> byWireName(String wireName) {
        if (wireName == null) {
            return Optional.empty();
        }
        for (ChangeOperationType type : values()) {
            if (type.wireName.equals(wireName.strip())) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
