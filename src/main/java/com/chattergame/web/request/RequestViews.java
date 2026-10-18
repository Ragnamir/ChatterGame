package com.chattergame.web.request;

import com.chattergame.request.GameRequest;
import com.chattergame.request.RequestOperation;
import com.chattergame.request.RequestService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Сборка {@link RequestView} из заявки и её операций. Отдельный компонент, потому что
 * представление нужно двум входам: очереди заявок и вызову модели, который заводит заявку
 * сам (`D-040`). Копия сборки разошлась бы на первом же новом поле.
 */
@Component
public class RequestViews {

    private final RequestService requests;

    public RequestViews(RequestService requests) {
        this.requests = requests;
    }

    public RequestView view(UUID gameId, GameRequest request) {
        List<RequestView.OperationView> operations = requests.operationsOf(request.id()).stream()
                .map(RequestViews::operation)
                .toList();
        return new RequestView(
                request.id().toString(),
                gameId.toString(),
                request.origin().name(),
                request.type().name(),
                string(request.fromMembershipId()),
                string(request.toCharacterId()),
                request.status().name(),
                request.error(),
                request.idempotencyKey(),
                request.createdAt().toString(),
                request.expiresAt() == null ? null : request.expiresAt().toString(),
                request.payload(),
                operations);
    }

    private static RequestView.OperationView operation(RequestOperation operation) {
        return new RequestView.OperationView(
                operation.id().toString(),
                operation.ordinal(),
                operation.op().wireName(),
                operation.statusOf().name(),
                // Обратимость — свойство вида операции, а не её текущего статуса (`D-010`):
                // клиент узнаёт, что откат невозможен, до нажатия, а не из отказа после.
                operation.op().reversible(),
                operation.payload(),
                operation.oldValue());
    }

    private static String string(UUID value) {
        return value == null ? null : value.toString();
    }
}
