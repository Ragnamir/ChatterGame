package com.chattergame.web.user;

import jakarta.validation.constraints.NotBlank;

/** Удаление аккаунта. Пароль обязателен: закрытие аккаунта необратимо (`D-095`). */
public record DeleteAccountRequest(@NotBlank String password) {
}
