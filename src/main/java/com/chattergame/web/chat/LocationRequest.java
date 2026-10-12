package com.chattergame.web.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Создание и правка локации. Поля совпадают с `REQUIREMENTS §7.1`: название и описание.
 * Картинка ставится отдельным запросом (`E05`): она ссылается на ассет, которого в теле
 * правки текста нет.
 */
public record LocationRequest(
        @NotBlank(message = "Укажите название локации")
        @Size(max = 200, message = "Название локации: до 200 знаков")
        String title,

        @Size(max = 4000, message = "Описание локации: до 4000 знаков")
        String description) {
}
