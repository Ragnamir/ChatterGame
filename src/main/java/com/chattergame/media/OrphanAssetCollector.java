package com.chattergame.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Орфан-сборка: ассеты, не упомянутые ни в одной живой сущности (`D-077`, `D-104`).
 *
 * <p>Сухой прогон — основной режим, а не отладочный: удаление картинок необратимо, а список
 * ссылок растёт по этапам (`D-106`), поэтому сначала сборщик называет кандидатов в логе, и
 * только явное `chattergame.media.orphan-purge=true` разрешает удалять. Умолчание выбрано
 * так потому, что пропущенная ссылка стоит лишнего диска, а преждевременное удаление —
 * потерянной картинки в живой игре.
 *
 * <p>Архивные локации и персонажи считаются ссылкой: на них опирается транскрипт (`D-077`).
 * Поэтому ассет архивного персонажа живой игры сборка не тронет — это отдельный случай
 * в критериях приёмки этапа, и он держится на запросе {@link AssetRepository#findUnreferenced}.
 */
@Component
public class OrphanAssetCollector {

    private static final Logger log = LoggerFactory.getLogger(OrphanAssetCollector.class);

    /** Временные файлы загрузки считаются мусором, только если заведомо брошены. */
    private static final long STALE_UPLOAD_AGE_SECONDS = 3600;

    private final AssetRepository assets;
    private final AssetStore store;
    private final MediaSettings settings;

    public OrphanAssetCollector(AssetRepository assets, AssetStore store, MediaSettings settings) {
        this.assets = assets;
        this.store = store;
        this.settings = settings;
    }

    @Scheduled(cron = "${chattergame.media.orphan-cron:0 0 4 * * *}")
    public void scheduled() {
        OrphanReport report = collect(!settings.orphanPurge());
        log.info("orphan-collect scheduled: deleted={}, wouldDelete={}, bytes={}",
                report.deleted().size(), report.candidates().size(), report.bytes());
    }

    /**
     * @param dryRun {@code true} — только перечислить кандидатов, ничего не удаляя
     * @return что удалено, что попало в список кандидатов и сколько байт это освободило
     */
    public OrphanReport collect(boolean dryRun) {
        Instant quarantine = Instant.now().minus(settings.orphanGrace());
        List<Asset> orphans = assets.findUnreferenced(quarantine);
        List<UUID> candidates = orphans.stream().map(Asset::id).toList();
        long bytes = orphans.stream().mapToLong(Asset::byteSize).sum();

        if (dryRun) {
            return new OrphanReport(List.of(), candidates, bytes, true);
        }
        List<UUID> deleted = new ArrayList<>();
        for (Asset orphan : orphans) {
            // Транзакции на весь прогон нет намеренно: каждая строка удаляется отдельно,
            // и оборванный прогон продолжается следующим, а не откатывается целиком.
            // От гонки с привязкой защищает карантин, а не блокировка.
            assets.purge(orphan.id());
            store.delete(orphan.storageKey());
            deleted.add(orphan.id());
        }
        purgeStaleUploads();
        return new OrphanReport(deleted, List.of(), bytes, false);
    }

    /**
     * Брошенные временные файлы загрузки. Свежие не трогаются: их может писать идущая
     * прямо сейчас загрузка, а она пишет во временный файл до переименования.
     */
    private void purgeStaleUploads() {
        Path root = settings.root().toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            return;
        }
        Instant threshold = Instant.now().minusSeconds(STALE_UPLOAD_AGE_SECONDS);
        try (Stream<Path> files = Files.list(root)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (name.startsWith(".upload-") && name.endsWith(".part")
                        && Files.getLastModifiedTime(file).toInstant().isBefore(threshold)) {
                    store.delete(name);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось просмотреть каталог медиа", e);
        }
    }

    /**
     * Итог прогона. `candidates` заполнен только при сухом прогоне, `deleted` — только при
     * настоящем: один и тот же список в двух полях читался бы как «удалено, но не удалено».
     */
    public record OrphanReport(List<UUID> deleted, List<UUID> candidates, long bytes, boolean dryRun) {
    }
}
