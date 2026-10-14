package com.chattergame.chat;

import java.time.Instant;
import java.util.UUID;

/**
 * Чат игры (`REQUIREMENTS §7`). На `E04` наполняется только {@link ChatKind#LOCATION}.
 *
 * <p>Архивация хранит причину и момент: без них игроку нечего показать вместо исчезнувшей
 * локации, а «выведена из игры» без причины — это молчание мастера (`D-009`). Отдельная
 * таблица `chat_state` из `PLAN` не заводится, потому что колонок достаточно (`D-103`).
 *
 * <p>Ссылка на картинку — `imageAssetId` (`E05`): одна картинка на локацию, галерей нет
 * (`D-098`). Хранится идентификатор, а не путь: путь меняется при переезде хранилища,
 * а идентификатор остаётся и он же служит проверке прав на отдачу (`D-075`).
 */
public record Chat(
        UUID id,
        UUID gameId,
        ChatKind kind,
        String title,
        String description,
        UUID imageAssetId,
        boolean archived,
        String archivedReason,
        Instant archivedAt,
        boolean disabled,
        Instant createdAt) {

    /** Максимум колонки `chat.title`; проверяется на сервисе ради текста ошибки, не только БД. */
    public static final int TITLE_MAX = 200;
}
