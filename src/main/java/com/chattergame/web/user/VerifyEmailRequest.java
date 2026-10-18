package com.chattergame.web.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Токен из ссылки подтверждения адреса (`O01`). */
public record VerifyEmailRequest(
        @NotBlank @Size(max = 128) String token) {
}
