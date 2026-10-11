package com.chattergame.llm;

import java.time.Instant;
import java.util.UUID;

/**
 * Ответ API по креденшалу LLM. Поля секрета здесь нет вообще, а не «есть, но пустое»:
 * так значение не может попасть в тело ответа ни при ошибке сериализации, ни при
 * случайном копировании записи (`D-093`).
 */
public record LlmCredentialView(
        UUID id, String label, String baseUrl, String model, boolean active, Instant createdAt) {
}
