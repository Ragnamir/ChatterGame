package com.chattergame.web.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Радиочат (`REQUIREMENTS §7.2`): название даёт мастер, состав — фиксированный список
 * персонажей, ради которого радио и существует (`D-039`). Список здесь полный, на замену:
 * «добавить одного» и «убрать одного» — одна и та же правка состава.
 */
public record RadioRequest(
        @NotBlank(message = "Укажите название радиочата")
        @Size(max = 200, message = "Название радиочата: до 200 знаков")
        String title,

        @NotNull(message = "Укажите список персонажей")
        List<UUID> characterIds) {
}
