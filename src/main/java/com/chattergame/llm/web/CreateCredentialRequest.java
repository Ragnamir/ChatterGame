package com.chattergame.llm.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Создание ключа LLM. `secret` — единственное место, где открытый ключ входит в приложение. */
public record CreateCredentialRequest(
        @Size(max = 64) String label,
        @NotBlank @Size(max = 255) String baseUrl,
        @NotBlank @Size(max = 128) String model,
        @NotBlank String secret) {
}
