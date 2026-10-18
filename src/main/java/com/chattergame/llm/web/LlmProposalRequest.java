package com.chattergame.llm.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Запрос к модели. `type` различает со-мастера и автопилота, а `characterId` и `chatId`
 * обязательны только автопилоту: он играет конкретного персонажа в конкретной сцене.
 */
public record LlmProposalRequest(
        String type,
        @NotBlank @Size(max = 4000) String instruction,
        UUID chatId,
        UUID characterId,
        String idempotencyKey) {
}
