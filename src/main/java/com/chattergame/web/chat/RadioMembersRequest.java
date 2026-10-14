package com.chattergame.web.chat;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Список участников радиочата (`REQUIREMENTS §7.2`). Пустой список допустим: он означает
 * «связь пока никого не соединяет», и это не ошибка ввода, а состояние до следующей правки.
 */
public record RadioMembersRequest(
        @NotNull(message = "Укажите список персонажей")
        List<UUID> characterIds) {
}
