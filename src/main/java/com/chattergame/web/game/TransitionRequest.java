package com.chattergame.web.game;

import jakarta.validation.constraints.NotBlank;

/**
 * Запрос перехода состояния. Переход передаётся машинным именем, а не отдельными
 * эндпоинтами: таблица разрешённых переходов — данные (`REQUIREMENTS §4`), и клиент
 * показывает ровно то, что сервер прислал в отчёте о доступных действиях.
 *
 * <p>`pauseReason` обязателен только для паузы; проверка живёт в `GameStateMachine`,
 * чтобы текст отказа был один и тот же из любого вызывающего слоя.
 */
public record TransitionRequest(@NotBlank String transition, String pauseReason) {
}
