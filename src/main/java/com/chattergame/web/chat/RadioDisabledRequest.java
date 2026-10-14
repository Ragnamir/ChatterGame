package com.chattergame.web.chat;

import jakarta.validation.constraints.NotNull;

/**
 * Включение и выключение радиочата (`REQUIREMENTS §7.2`). Отдельное тело, а не путь
 * «включить»/«выключить»: состояние одно, и повтор с тем же значением ничего не меняет
 * (`D-010`).
 */
public record RadioDisabledRequest(
        @NotNull(message = "Укажите состояние радиочата")
        Boolean disabled) {
}
