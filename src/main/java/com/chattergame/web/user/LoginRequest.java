package com.chattergame.web.user;

import jakarta.validation.constraints.NotBlank;

/** Вход по логину и паролю. Емейл логином не является (`PLAN` E02). */
public record LoginRequest(@NotBlank String login, @NotBlank String password) {
}
