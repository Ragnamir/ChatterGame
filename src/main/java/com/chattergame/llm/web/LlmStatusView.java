package com.chattergame.llm.web;

/**
 * Доступность опций модели. `reason` — машинный код, по которому клиент решает, что
 * показать: при `llm_not_configured` панель модели скрыта целиком (`D-042`).
 */
public record LlmStatusView(boolean available, String reason) {
}
