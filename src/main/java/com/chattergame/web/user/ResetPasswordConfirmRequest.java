package com.chattergame.web.user;

import jakarta.validation.constraints.NotBlank;

/** Подтверждение сброса: токен из письма и новый пароль. */
public record ResetPasswordConfirmRequest(@NotBlank String token, @NotBlank String newPassword) {
}
