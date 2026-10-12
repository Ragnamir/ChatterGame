package com.chattergame.media;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Настройки медиа, читаются из окружения с умолчаниями из `D-077`.
 *
 * <p>`@EnableScheduling` включается здесь, а не в классе приложения: единственная
 * периодическая задача проекта — орфан-сборка, и её расписание не должно выглядеть частью
 * каркаса.
 */
@Configuration
@EnableScheduling
public class MediaConfiguration {

    @Bean
    MediaSettings mediaSettings(
            @Value("${chattergame.media.root:build/media}") String root,
            @Value("${chattergame.media.max-file-bytes:5242880}") long maxFileBytes,
            @Value("${chattergame.media.max-game-bytes:209715200}") long maxGameBytes,
            @Value("${chattergame.media.max-side-pixels:4096}") int maxSidePixels,
            @Value("${chattergame.media.max-concurrent-uploads:2}") int maxConcurrentUploads,
            @Value("${chattergame.media.orphan-cron:0 0 4 * * *}") String orphanCron,
            @Value("${chattergame.media.orphan-grace-hours:24}") long orphanGraceHours,
            @Value("${chattergame.media.orphan-purge:false}") boolean orphanPurge) {
        return new MediaSettings(
                Path.of(root),
                maxFileBytes,
                maxGameBytes,
                maxSidePixels,
                maxConcurrentUploads,
                orphanCron,
                Duration.ofHours(orphanGraceHours),
                orphanPurge);
    }
}
