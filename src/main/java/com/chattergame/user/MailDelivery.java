package com.chattergame.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Единственная точка отправки писем (`O01`, `D-143`).
 *
 * <p>Счётчики по виду письма и исходу — это и есть мониторинг доставки: по ним видно, что
 * письма подтверждения перестали уходить, не читая чужих адресов в логах. Ни адрес, ни
 * текст письма в лог не попадают: адрес — персональные данные, текст содержит токен.
 *
 * <p>Отказ транспорта не пробрасывается: почта — внешняя система, и её недоступность не
 * должна отменять уже принятое действие (регистрацию, смену адреса). Исключение составляет
 * явный запрос письма из профиля — там отказ обязан дойти до человека, и это решает
 * вызывающий код, проверяя {@link Result}.
 */
@Component
public class MailDelivery {

    private static final Logger log = LoggerFactory.getLogger(MailDelivery.class);

    private final MailSender sender;
    private final Map<String, AtomicLong> sent = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> failed = new ConcurrentHashMap<>();

    public MailDelivery(MailSender sender) {
        this.sender = sender;
    }

    public Result send(String kind, String email, MailTemplates.Message message) {
        try {
            sender.send(email, message.subject(), message.text());
            sent.computeIfAbsent(kind, ignored -> new AtomicLong()).incrementAndGet();
            return new Result(true, null);
        } catch (RuntimeException failure) {
            failed.computeIfAbsent(kind, ignored -> new AtomicLong()).incrementAndGet();
            log.warn("mail delivery failed kind={} error={}", kind, failure.getClass().getSimpleName());
            return new Result(false, failure);
        }
    }

    /** Снимок счётчиков: вид письма → доставлено / не доставлено. */
    public Map<String, Counts> stats() {
        Map<String, Counts> all = new ConcurrentHashMap<>();
        sent.forEach((kind, value) -> all.put(kind, new Counts(value.get(), 0)));
        failed.forEach((kind, value) -> all.merge(
                kind, new Counts(0, value.get()), (left, right) ->
                        new Counts(left.sent() + right.sent(), left.failed() + right.failed())));
        return Map.copyOf(all);
    }

    public record Result(boolean delivered, RuntimeException failure) {
    }

    public record Counts(long sent, long failed) {
    }
}
