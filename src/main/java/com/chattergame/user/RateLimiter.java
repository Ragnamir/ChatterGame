package com.chattergame.user;

import com.chattergame.util.ApiException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ограничитель частоты в памяти. Продакшн — один контейнер (`D-072`), поэтому общее
 * хранилище счётчиков не нужно, а лишняя таблица потребовала бы миграции и уборки
 * мусора. Перезапуск сбрасывает окно: для сброса пароля это приемлемо, потому что
 * цель ограничителя — не бесконечный подбор, а затухание потока.
 *
 * <p>Ключи ограничиваются и по адресу, и по клиенту: иначе перебор одного адреса
 * с разных адресов или подстановка чужих адресов оставались бы безнаказанными.
 */
@Component
public class RateLimiter {

    private final Clock clock;
    private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();

    public RateLimiter(Clock clock) {
        this.clock = clock;
    }

    public void acquire(String key, int limit, Duration window) {
        Instant now = clock.instant();
        Deque<Instant> stamps = hits.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        boolean allowed;
        synchronized (stamps) {
            Instant threshold = now.minus(window);
            while (!stamps.isEmpty() && stamps.peekFirst().isBefore(threshold)) {
                stamps.pollFirst();
            }
            allowed = stamps.size() < limit;
            if (allowed) {
                stamps.addLast(now);
            }
        }
        if (!allowed) {
            throw ApiException.tooManyRequests(
                    "rate_limited", "Слишком много запросов. Попробуйте позже.");
        }
        if (stamps.isEmpty()) {
            hits.remove(key, stamps);
        }
    }
}
