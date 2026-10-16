package com.chattergame.web.request;

import com.chattergame.request.GameRequest;
import com.chattergame.request.RequestOperation;
import com.chattergame.request.RequestOrigin;
import com.chattergame.request.RequestService;
import com.chattergame.request.RequestStatus;
import com.chattergame.request.RequestType;
import com.chattergame.game.MembershipRepository;
import com.chattergame.game.MembershipRole;
import com.chattergame.util.ApiException;
import com.chattergame.web.AuthContext;
import com.chattergame.web.AuthContextResolver;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Заявки: подача, очередь мастера, частичное одобрение и откат (`PLAN` E14).
 *
 * <p>Контроллер умеет ровно две вещи — разрешить логин в участие и разобрать строки
 * словарей в значения. Мутаций мира здесь нет: применяет {@link RequestService}, и он же
 * проверяет мастера на каждое решение, потому что «одобряет только мастер» — правило
 * домена, а не маршрута (`D-034`).
 *
 * <p>Список маршрутов намеренно не содержит отдельной ручки для `LLM`: источник заявки —
 * поле `origin`, а не второй вход. Движок один (`D-040`), и второй маршрут развёл бы
 * проверки прав по двум местам.
 */
@RestController
@RequestMapping("/games/{gameId}/requests")
public class RequestController {

    private final RequestService requests;
    private final MembershipRepository memberships;

    public RequestController(RequestService requests, MembershipRepository memberships) {
        this.requests = requests;
        this.memberships = memberships;
    }

    /**
     * Подача заявки. Игрок приходит сюда с `origin = PLAYER` (по умолчанию), мастер — с
     * `MASTER`, а предложение модели — с `LLM` и тем же ключом идемпотентности, что и
     * запрос к провайдеру: повтор доставки не создаёт вторую заявку (`D-043`).
     */
    @PostMapping
    public ResponseEntity<RequestView> submit(
            @PathVariable UUID gameId, @Valid @RequestBody SubmitRequestRequest request, AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        GameRequest submitted = requests.submit(
                auth.user().id(),
                gameId,
                new RequestService.Submission(
                        origin(request.origin()),
                        type(request.type()),
                        request.toCharacterId(),
                        request.payload(),
                        request.idempotencyKey(),
                        request.expiresAt()));
        return ResponseEntity.ok(view(gameId, submitted));
    }

    /** Очередь заявок: мастеру — вся, участнику — свои (`REQUIREMENTS §17`). */
    @GetMapping
    public List<RequestView> list(
            @PathVariable UUID gameId,
            @RequestParam(required = false) String status,
            AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        return requests.list(auth.user().id(), gameId, status(status)).stream()
                .map(request -> view(gameId, request))
                .toList();
    }

    @GetMapping("/{requestId}")
    public RequestView get(
            @PathVariable UUID gameId, @PathVariable UUID requestId, AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        GameRequest request = requests.find(gameId, requestId);
        requireVisible(auth.user().id(), gameId, request);
        return view(gameId, request);
    }

    /**
     * Решение мастера: список номеров операций к применению. Пустой список — полное
     * отклонение, и это не то же самое, что отсутствие тела: «одобряю ничего» — осознанный
     * ответ, и он обязан доходить до сервиса, а не читаться как «поле забыли».
     */
    @PostMapping("/{requestId}/decision")
    public RequestView decide(
            @PathVariable UUID gameId,
            @PathVariable UUID requestId,
            @RequestBody(required = false) DecisionRequest request,
            AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        List<Integer> approve = request == null || request.approve() == null
                ? List.of()
                : List.copyOf(request.approve());
        return view(gameId, requests.decide(auth.user().id(), gameId, requestId, approve));
    }

    /** Откат применённых операций: обратимые возвращают прежнее значение из журнала. */
    @PostMapping("/{requestId}/rollback")
    public RequestView rollback(
            @PathVariable UUID gameId,
            @PathVariable UUID requestId,
            @RequestBody(required = false) DecisionRequest request,
            AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        List<Integer> ordinals = request == null || request.approve() == null
                ? null
                : List.copyOf(request.approve());
        return view(gameId, requests.rollback(auth.user().id(), gameId, requestId, ordinals));
    }

    private RequestView view(UUID gameId, GameRequest request) {
        List<RequestView.OperationView> operations = requests.operationsOf(request.id()).stream()
                .map(RequestController::operation)
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
                reason(operation),
                operation.payload(),
                operation.oldValue());
    }

    /**
     * Причина выводится из свойства операции, а не хранится рядом со статусом: статус
     * меняется, а «удаление запрещено» — никогда (`D-010`). Поэтому клиент узнаёт о
     * необратимости до нажатия, а не из отказа после.
     */
    private static String reason(RequestOperation operation) {
        return operation.op().reversible() ? null : "необратима: удаление запрещено (D-010)";
    }

    /** Чужая заявка мастеру видна, участнику — нет: очередь мастера не публичная лента. */
    private void requireVisible(UUID userId, UUID gameId, GameRequest request) {
        var membership = memberships.find(gameId, userId)
                .orElseThrow(() -> ApiException.forbidden("not_a_member", "Нет доступа к этой игре"));
        if (membership.role() == MembershipRole.MASTER) {
            return;
        }
        if (!membership.id().equals(request.fromMembershipId())) {
            throw ApiException.forbidden("request_not_visible", "Эта заявка подана не вами");
        }
    }

    private static String string(UUID value) {
        return value == null ? null : value.toString();
    }

    private static RequestOrigin origin(String raw) {
        if (raw == null || raw.isBlank()) {
            return RequestOrigin.PLAYER;
        }
        try {
            return RequestOrigin.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("unknown_request_origin", "origin — MASTER, PLAYER или LLM");
        }
    }

    private static RequestType type(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest("request_type_required", "Укажите тип заявки");
        }
        try {
            return RequestType.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("unknown_request_type", "Неизвестный тип заявки");
        }
    }

    private static RequestStatus status(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return RequestStatus.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("unknown_request_status", "Неизвестный статус заявки");
        }
    }
}
