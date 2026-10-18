package com.chattergame.llm;

import com.chattergame.util.ApiException;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Ограниченный пул вызовов модели и одно ожидание на игру (`REQUIREMENTS §18`, `D-043`).
 *
 * <p>Две границы, и обе обязательны. Пул ограничен, потому что каждый вызов держит поток
 * десятки секунд; очередь ограничена, потому что бесконечная превратила бы отказ провайдера
 * в рост памяти. Один вызов на игру — потому что со-мастер и автопилот читают один и тот же
 * мир, и два одновременных предложения по одной сцене бессмысленны: мастер всё равно
 * одобряет по одному.
 *
 * <p>Метод не открывает транзакцию и не должен вызываться изнутри `@Transactional`
 * (`D-044`): проверка на это — статическая, в гейте.
 */
@Component
public class LlmCallExecutor {

    private final ThreadPoolExecutor pool;
    private final Map<UUID, Boolean> inFlight = new ConcurrentHashMap<>();

    public LlmCallExecutor(
            @Value("${chattergame.llm.threads:2}") int threads,
            @Value("${chattergame.llm.queue:16}") int queue) {
        this.pool = new ThreadPoolExecutor(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, queue)),
                runnable -> {
                    Thread thread = new Thread(runnable, "llm-call");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Выполняет вызов в пуле. Занятость игры и переполнение очереди — `409 llm_busy`:
     * это состояние, а не ошибка ввода, и повтор через минуту его снимет.
     */
    public <T> T call(UUID gameId, long timeoutMillis, Callable<T> task) {
        if (inFlight.putIfAbsent(gameId, Boolean.TRUE) != null) {
            throw ApiException.conflict(
                    "llm_busy", "По этой игре уже идёт запрос к модели: дождитесь ответа");
        }
        try {
            Future<T> future = pool.submit(task);
            try {
                return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                throw new LlmCallException(LlmErrorKind.TIMEOUT, "Вызов модели не уложился во время");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                throw new LlmCallException(LlmErrorKind.TIMEOUT, "Ожидание ответа модели прервано");
            } catch (ExecutionException e) {
                if (e.getCause() instanceof LlmCallException call) {
                    throw call;
                }
                throw new LlmCallException(
                        LlmErrorKind.PROVIDER_ERROR, "Вызов модели завершился ошибкой", e.getCause());
            } catch (RejectedExecutionException e) {
                throw ApiException.tooManyRequests(
                        "llm_busy", "Очередь запросов к модели переполнена: повторите позже");
            }
        } finally {
            inFlight.remove(gameId);
        }
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }

    /**
     * Загрузка пула (`PLAN` O02): «глубина очереди» — это ожидающие вызова, а `active` —
     * сколько игр ждёт ответа прямо сейчас. Без этих двух чисел переполнение очереди
     * выглядит для оператора как внезапные `429` без причины.
     */
    public Load load() {
        return new Load(
                pool.getActiveCount(),
                pool.getQueue().size(),
                pool.getMaximumPoolSize() + pool.getQueue().remainingCapacity() + pool.getQueue().size());
    }

    public record Load(int active, int queued, int capacity) {
    }
}
