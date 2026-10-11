package com.chattergame.web.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Регистрация. Пароль проверяется сервисом, а не аннотацией: границы 8–128 заданы решением
 * (`D-096`), и иметь их в двух местах — значит иметь две разные границы.
 */
public record RegisterRequest(
        @NotBlank @Size(max = 64) String login,
        @NotBlank String password,
        @Size(max = 128) String displayName,
        @Size(max = 320) String email) {
}
