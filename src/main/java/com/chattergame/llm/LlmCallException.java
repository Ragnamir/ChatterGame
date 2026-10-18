package com.chattergame.llm;

/**
 * Отказ вызова модели с классом причины (`D-043`). Наследуется от `RuntimeException`, чтобы
 * не заражать сигнатуры слоёв, но несёт {@link LlmErrorKind}: обработчик записывает класс в
 * `request.error`, а не пересказывает текст провайдера.
 *
 * <p>Текст исключения не содержит ключа и тела промпта: он попадает в лог, а ключ — чужие
 * деньги и чужая квота (`D-093`).
 */
public class LlmCallException extends RuntimeException {

    private final LlmErrorKind kind;

    public LlmCallException(LlmErrorKind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public LlmCallException(LlmErrorKind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public LlmErrorKind kind() {
        return kind;
    }

    public String code() {
        return kind.code();
    }
}
