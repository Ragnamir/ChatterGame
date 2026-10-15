package com.chattergame.web.game;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Приглашение в игру (`PLAN` E13). Цель адресуется логином существующего аккаунта
 * (`D-131`): несуществующий логин отвергается до всякой записи, поэтому в теле нет
 * идентификатора пользователя, который клиент мог бы выдумать.
 *
 * <p>`characterId` необязателен: приглашение без слота возможно, а бронь появляется
 * вместе с обещанным персонажем (`D-130`). `role` по умолчанию `PLAYER`; `OBSERVER`
 * позволяет открыть приватную игру для чтения, и тогда слот не бронируется вовсе.
 */
public record InvitationRequest(
        @NotBlank(message = "Укажите логин приглашаемого")
        @Size(max = 64, message = "Логин слишком длинный")
        String login,
        String role,
        UUID characterId) {
}
