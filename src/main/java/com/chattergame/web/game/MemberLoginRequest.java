package com.chattergame.web.game;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Адресация участия логином для бана и снятия бана (`D-133`). Тот же адрес, что у
 * приглашения (`D-131`): искать человека по идентификатору мастеру негде.
 */
public record MemberLoginRequest(
        @NotBlank(message = "Укажите логин участника")
        @Size(max = 64, message = "Логин слишком длинный")
        String login) {
}
