package com.chattergame.llm;

import java.time.Instant;
import java.util.UUID;

/**
 * Ключ LLM пользователя. Секрет лежит зашифрованным (`D-093`): в записи нет ни открытого
 * ключа, ни поля, из которого его можно собрать. Наружу отдаётся {@link LlmCredentialView}.
 */
public record LlmCredential(
        UUID id,
        UUID userId,
        String label,
        String baseUrl,
        String model,
        byte[] secretCiphertext,
        byte[] secretIv,
        byte[] secretTag,
        boolean active,
        Instant createdAt,
        Instant updatedAt) {

    /** Секрет наружу не выходит: только маска (`D-093`). */
    public LlmCredentialView view() {
        return new LlmCredentialView(id, label, baseUrl, model, active, createdAt);
    }
}
