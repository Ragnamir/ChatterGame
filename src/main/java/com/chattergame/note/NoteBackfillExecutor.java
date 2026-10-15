package com.chattergame.note;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.PreDestroy;

/**
 * Асинхронный запуск досчёта подсветки (`D-087`).
 *
 * <p>Досчёт вынесен из транзакции запроса заметки, и это не оптимизация: пересчёт идёт по
 * всем строкам материала целевого субъекта, и держать его в запросе значило бы заставить
 * игрока ждать. Поэтому заметка создаётся сразу с признаком `matching_pending`, а работа
 * уходит сюда; признак опускает {@link NoteBackfillService} по завершении.
 *
 * <p>Исполнитель однопоточный и один на приложение: пересчёты сериализуются, а не
 * конкурируют за одни и те же строки `message`. При 4–10 игроках очередь досчётов
 * практически пуста, и параллелизм здесь дал бы только гонки.
 *
 * <p>Плановый прогон — страховка: если узел упал в момент досчёта, заметка осталась с
 * поднятым признаком, и следующий прогон её доберёт. Именно поэтому `matching_pending`
 * хранится в БД, а не в памяти: он обязан переживать перезапуск.
 */
@Component
public class NoteBackfillExecutor {

    private static final Logger log = LoggerFactory.getLogger(NoteBackfillExecutor.class);

    private final NoteBackfillService backfill;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            runnable -> {
                Thread thread = new Thread(runnable, "note-backfill");
                thread.setDaemon(true);
                return thread;
            });
    private final AtomicBoolean queued = new AtomicBoolean();
    private final AtomicReference<NoteBackfillService.Report> lastRun = new AtomicReference<>();

    public NoteBackfillExecutor(NoteBackfillService backfill) {
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

    /**
     * Синхронный прогон: тесты и плановый обход ждут результата, а не гадают, успела ли
     * задача. Возвращает счётчики — по ним проверяется, что область ограничена субъектом.
     */
    public NoteBackfillService.Report runNow() {
        NoteBackfillService.Report report = backfill.backfillAll();
        lastRun.set(report);
        return report;
    }

    /** Досчёт одной заметки синхронно: проверке не нужен весь список незавершённых. */
    public NoteBackfillService.Report runNow(Note note) {
        NoteBackfillService.Report report = backfill.backfillNote(note);
        lastRun.set(report);
        return report;
    }

    public NoteBackfillService.Report lastRun() {
        return lastRun.get();
    }

    /**
     * Плановый обход незавершённых досчётов. Он не заменяет асинхронный запуск при создании
     * заметки, а страхует его: заметка с поднятым признаком, забытая упавшим узлом, должна
     * быть добрана без вмешательства человека.
     */
    @Scheduled(fixedDelayString = "${chattergame.notes.backfill-delay:300000}")
    public void sweep() {
        runQuietly();
    }

    private void runQuietly() {
        try {
            NoteBackfillService.Report report = runNow();
            if (report.notes() > 0) {
                log.info("note-backfill: notes={}, rows={}", report.notes(), report.rows());
            }
        } catch (RuntimeException e) {
            // Признак остаётся поднятым: следующий прогон повторит попытку. Проброс
            // исключения уронил бы планировщик целиком и остановил чужие задачи.
            log.warn("note-backfill не завершён, повтор следующим прогоном", e);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
