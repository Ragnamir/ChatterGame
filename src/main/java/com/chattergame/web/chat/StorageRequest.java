package com.chattergame.web.chat;

import com.chattergame.item.StorageKind;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Хранилище в запросе мастера (`REQUIREMENTS §10`, `D-016`). Владелец задаётся типом:
 * у хранилища персонажа заполнен `characterId`, у локационного и сундука — `chatId`.
 * Проверка соответствия живёт в сервисе, потому что зависит от `kind`.
 *
 * <p>Имя рекорда `chatId`, хотя в мире это локация: так называется колонка в схеме, и
 * переименовывать поле на границе значило бы держать два слова для одной сущности.
 */
public record StorageRequest(
        @NotNull(message = "Укажите тип хранилища")
        StorageKind kind,
        UUID chatId,
        UUID characterId,
        String title,
        String description) {
}
