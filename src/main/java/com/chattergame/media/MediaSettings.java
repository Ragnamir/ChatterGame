package com.chattergame.media;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Границы медиа из `REQUIREMENTS §19` и `D-077`. Вынесены в настройки, а не в константы
 * на месте проверки: 5 МБ на файл и 200 МБ на игру — это то, что придётся поднимать при
 * росте числа игр, и поднимать это нужно конфигурацией, а не релизом.
 *
 * @param root                каталог содержимого ассетов; вне него `AssetStore` не пишет
 * @param maxFileBytes        лимит байт на файл (2–5 МБ по `D-077`)
 * @param maxGameBytes        квота на игру (100–200 МБ по `D-077`)
 * @param maxSidePixels       лимит стороны при чтении заголовка (`D-076`)
 * @param maxConcurrentUploads одновременные загрузки одного пользователя (`D-077`)
 * @param orphanCron          расписание орфан-сборки
 * @param orphanGrace         карантин для свежих ассетов: они могут ещё ждать привязки
 * @param orphanPurge         удалять ли найденное; по умолчанию сборка только сообщает
 */
public record MediaSettings(
        Path root,
        long maxFileBytes,
        long maxGameBytes,
        int maxSidePixels,
        int maxConcurrentUploads,
        String orphanCron,
        Duration orphanGrace,
        boolean orphanPurge) {
}
