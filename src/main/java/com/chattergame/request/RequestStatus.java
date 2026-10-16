package com.chattergame.request;

/**
 * Статус заявки (`REQUIREMENTS §17`). Перечисление фиксируется целиком, включая состояния
 * LLM-вызова (`RUNNING`, `FAILED`): словарь — контракт, и расширять его на следующем этапе
 * значило бы менять формат, который уже читают клиенты.
 *
 * <p>`PARTIALLY_APPROVED` — не «почти одобрено», а точный итог: часть операций применена,
 * часть отклонена. Отдельный статус нужен потому, что `D-041` требует частичного одобрения
 * по операциям, и «одобрено» скрыло бы, что часть правок не дошла.
 */
public enum RequestStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    APPROVED,
    REJECTED,
    PARTIALLY_APPROVED,
    EXPIRED;

    /** Заявка ещё ждёт решения: только такие берёт автодекларация просрочки и одобрение. */
    public boolean open() {
        return this == PENDING || this == RUNNING;
    }
}
