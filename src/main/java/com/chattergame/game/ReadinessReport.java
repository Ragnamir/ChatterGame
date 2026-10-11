package com.chattergame.game;

import java.util.List;

/**
 * Отчёт валидации готовности (`REQUIREMENTS §20`). Разделение на блокирующие и
 * предупреждающие замечания — не оформление: блокирующие запрещают `DRAFT → READY`,
 * предупреждающие не мешают мастеру объявить готовность, потому что «флаг `READY`
 * остаётся за мастером».
 *
 * <p>Замечание несёт машинный код и текст: код нужен фронтенду и тестам, текст — мастеру.
 * Список пустой вместо `null`.
 */
public record ReadinessReport(List<Finding> blocking, List<Finding> warning) {

    public record Finding(String code, String message) {
    }

    public static ReadinessReport ok() {
        return new ReadinessReport(List.of(), List.of());
    }

    public boolean canBecomeReady() {
        return blocking.isEmpty();
    }

    public boolean hasWarnings() {
        return !warning.isEmpty();
    }
}
