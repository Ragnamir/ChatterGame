package com.chattergame.message;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Асинхронный запуск досчёта видимости наблюдателя (`D-086`).
 *
 * <p>Досчёт вынесен из транзакции входа, потому что он переписывает историю всех открытых
 * чатов: держать его в запросе значило бы заставить человека ждать. Поэтому признак
 * `backfill_pending` ставится при входе, а работа уходит сюда.
 *
 * <p>Исполнитель однопоточный и один на приложение: досчёты сериализуются, а не конкурируют
 * за одни и те же строки `inbox_entry`. При 4–10 игроках очередь практически пуста, и
 * параллелизм здесь дал бы только гонки. Плановый обход — страховка от упавшего узла:
 * признак лежит в БД именно за этим (`D-086`).
 */
@Component
public class VisibilityBackfillExecutor {

    private static final Logger log = LoggerFactory.getLogger(VisibilityBackfillExecutor.class);

    private final VisibilityBackfillService backfill;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            runnable -> {
                Thread thread = new Thread(runnable, "visibility-backfill");
                thread.setDaemon(true);
                return thread;
            });
    private final AtomicBoolean queued = new AtomicBoolean();
    private final AtomicReference<VisibilityBackfillService.Report> lastRun = new AtomicReference<>();

    public VisibilityBackfillExecutor(VisibilityBackfillService backfill) {
        this.backfill = backfill;
    }

    /** Ставит досчёт в очередь. Повторный вызов до начала работы второй задачи не создаёт. */
    public void schedule() {
        if (!queued.compareAndSet(false, true)) {
            return;
        }
        executor.execute(() -> {
            queued.set(false);
            runQuietly();
        });
    }

    /** Синхронный прогон: тесты ждут результата, а не гадают, успела ли задача. */
    public VisibilityBackfillService.Report runNow() {
        VisibilityBackfillService.Report report = backfill.backfillAll();
        lastRun.set(report);
        return report;
    }

    public VisibilityBackfillService.Report lastRun() {
        return lastRun.get();
    }

    @Scheduled(fixedDelayString = "${chattergame.visibility.backfill-delay:300000}")
    public void sweep() {
        runQuietly();
    }

    private void runQuietly() {
        try {
            VisibilityBackfillService.Report report = runNow();
            if (report.memberships() > 0) {
                log.info("visibility-backfill: memberships={}, rows={}",
                        report.memberships(), report.rows());
            }
        } catch (RuntimeException e) {
            // Признак остаётся поднятым: следующий прогон повторит попытку. Проброс исключения
            // уронил бы планировщик целиком и остановил чужие задачи.
            log.warn("visibility-backfill не завершён, повтор следующим прогоном", e);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
