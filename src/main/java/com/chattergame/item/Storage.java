package com.chattergame.item;

import java.time.Instant;
import java.util.UUID;

/**
 * Хранилище (`REQUIREMENTS §10`, `D-016`). Самостоятельная сущность: у сундука есть
 * описание и картинка, у хранилища персонажа — владелец, и правило «кто видит содержимое»
 * записано свойством хранилища, а не исключением в коде перемещения.
 *
 * <p>Привязка ровно одна: `chatId` у локационного хранилища и сундука, `characterId` у двух
 * хранилищ персонажа. Держит это проверка целостности в базе, а не соглашение.
 *
 * <p>`isOpen` — единственная ось доступа для `LOCATION` и `CHEST`: у хранилища нет воли,
 * поэтому открытое доступно всем присутствующим (`D-110`). Для хранилищ персонажа открытие
 * третьему лицу права взять не даёт: брать можно только с разрешения владельца (`D-017`).
 */
public record Storage(
        UUID id,
        UUID gameId,
        StorageKind kind,
        UUID chatId,
        UUID characterId,
        String title,
        String description,
        UUID imageAssetId,
        boolean open,
        Instant createdAt) {

    /** Максимум колонки `storage.title`; проверяется на сервисе ради текста ошибки. */
    public static final int TITLE_MAX = 200;
}
