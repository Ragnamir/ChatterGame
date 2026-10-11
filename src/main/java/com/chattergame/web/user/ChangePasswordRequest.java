package com.chattergame.web.user;

import jakarta.validation.constraints.NotBlank;

/**
 * Смена пароля. Текущий пароль требуется, потому что запрос уходит с уже открытой сессией:
 * без него угнанная cookie позволяла бы сменить пароль и отобрать аккаунт.
 */
public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {
}
