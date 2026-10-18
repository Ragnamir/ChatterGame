package com.chattergame.llm;

import java.time.Duration;

/**
 * Адаптер к провайдеру (`REQUIREMENTS §18`, `D-092`). Абстракция названа в требованиях,
 * поэтому она одна: второй провайдер — это вторая реализация, а не вторая подсистема.
 *
 * <p>Контракт намеренно узкий — «системная подсказка и запрос, ответ текстом». Формат
 * `chat/completions` покрывает облачные сервисы, агрегаторы и локальный запуск; всё, что
 * вендор-специфично, не обещается (`D-092`).
 */
public interface LlmProvider {

    /**
     * Вызов модели. Реализация обязана укладываться в {@code timeout} и бросать
     * {@link LlmCallException} с классом причины, а не голым `IOException`: класс отказа —
     * часть пользовательского состояния (`D-043`).
     */
    String complete(Request request);

    record Request(
            String baseUrl,
            String model,
            String apiKey,
            String system,
            String user,
            Duration timeout) {
    }
}
