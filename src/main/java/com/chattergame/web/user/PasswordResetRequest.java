package com.chattergame.web.user;

import jakarta.validation.constraints.NotBlank;

/**
 * Запрос письма для сброса пароля. Ответ на этот запрос не зависит от того, существует
 * ли адрес: одинаковый статус и одинаковое тело (`D-079`).
 */
public record PasswordResetRequest(@NotBlank String email) {
}
