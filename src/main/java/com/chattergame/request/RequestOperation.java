package com.chattergame.request;

import java.util.Map;
import java.util.UUID;

/**
 * Операция заявки (`request_operation`). `ordinal` — порядок применения: операции заявки
 * зависят друг от друга (`create_item` кладёт предмет в хранилище, `move_character` требует
 * персонажа на месте), поэтому применяются по возрастанию, а откатываются в обратном порядке.
 *
 * <p>`oldValue` — снимок прежнего значения, снятый при применении. Он нужен откату, но
 * источником истины остаётся журнал (`D-033`): откат читает значение из события, а здесь
 * оно лежит для чтения панелью мастера, чтобы дифф не собирался вторым обходом журнала.
 */
public record RequestOperation(
        UUID id,
        UUID requestId,
        int ordinal,
        ChangeOperationType op,
        Map<String, Object> payload,
        String status,
        Map<String, Object> oldValue) {

    public OperationStatus statusOf() {
        return status == null ? OperationStatus.PENDING : OperationStatus.valueOf(status);
    }
}
