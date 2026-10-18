package com.chattergame.request;

import com.chattergame.character.CharacterField;
import com.chattergame.game.GameMutationService;
import com.chattergame.game.Membership;
import com.chattergame.game.MembershipRepository;
import com.chattergame.game.MembershipRole;
import com.chattergame.item.ItemSize;
import com.chattergame.item.ItemType;
import com.chattergame.journal.GameEvent;
import com.chattergame.journal.GameEventRepository;
import com.chattergame.journal.GameEventType;
import com.chattergame.message.MessageKind;
import com.chattergame.message.MessageService;
import com.chattergame.message.SubjectKind;
import com.chattergame.note.NoteService;
import com.chattergame.util.ApiException;
import com.chattergame.util.Uuids;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Заявки и машина применения change set (`PLAN` E14, `D-040`, `D-041`).
 *
 * <p>Один движок на три источника: просьбу игрока, правку мастера и предложение модели.
 * Разница между ними — только {@link RequestOrigin}; применение, откат и хранение общие.
 * Пока генератора payload нет (`CO_MASTER`/`AUTOPILOT` — предмет `F05`), заявки с `LLM`
 * принимаются через тот же вход, что и прочие: контракт готов, а потребитель появится
 * позже, и подменять его заглушкой значило бы строить вторую сущность впустую.
 *
 * <p>Три вещи, за которые здесь держится корректность:
 *
 * <ul>
 *   <li>Заявка игрока <em>никогда</em> не меняет мир: путь применения начинается с проверки
 *       мастера, а {@link #submit} только пишет строку. Иначе «предложить» и «сделать»
 *       слились бы, и одобрение стало бы формальностью.
 *   <li>Каждая операция применяется своей транзакцией и своей мутацией через
 *       {@link GameMutationService} (`D-034`). Общей транзакции на всю заявку нет намеренно:
 *       частичное одобрение (`D-041`) — это именно частичный результат, и общая транзакция
 *       откатила бы уже применённые операции при первой же неудачной.
 *   <li>Откат восстанавливает значение, взятое из журнала: мутации заявки кладут старое
 *       значение в `payload` события, и откат читает его оттуда, а не из таблицы, которую
 *       сам же и меняет (`D-033`).
 * </ul>
 */
@Service
public class RequestService {

    /** Сколько заявка ждёт решения, если срок не назван явно (`PLAN` E14, задача 6). */
    private static final Duration DEFAULT_TTL = Duration.ofDays(14);

    private final GameRequestRepository requests;
    private final GameEventRepository events;
    private final RequestOperationRepository operations;
    private final MembershipRepository memberships;
    private final GameMutationService mutations;
    private final MessageService messages;
    private final NoteService notes;
    private final Duration defaultTtl;

    public RequestService(
            GameRequestRepository requests,
            GameEventRepository events,
            RequestOperationRepository operations,
            MembershipRepository memberships,
            GameMutationService mutations,
            MessageService messages,
            NoteService notes,
            @Value("${chattergame.requests.ttl:P14D}") Duration defaultTtl) {
        this.requests = requests;
        this.events = events;
        this.operations = operations;
        this.memberships = memberships;
        this.mutations = mutations;
        this.messages = messages;
        this.notes = notes;
        this.defaultTtl = defaultTtl == null ? DEFAULT_TTL : defaultTtl;
    }

    /**
     * Подача заявки (`REQUIREMENTS §17`). Мир не меняется ни в одной ветке: невалидный
     * payload отдаётся строкой `FAILED` с причиной (`D-043`), а не исключением, потому что
     * заявка обязана остаться в истории — иначе у мастера не будет, что показать автору.
     *
     * <p>Повтор с тем же ключом возвращает прежнюю заявку и не создаёт вторую (`PLAN` E14,
     * задача 6). Ключ уникален в пределах игры, поэтому ретрай сети не размножает заявки.
     * Транзакция одна на заявку и её операции: строка без операций была бы заявкой, которую
     * мастер видит и не может одобрить.
     */
    @Transactional
    public GameRequest submit(UUID actorUserId, UUID gameId, Submission submission) {
        Membership author = requireParticipant(actorUserId, gameId);
        RequestOrigin origin = submission.origin() == null ? RequestOrigin.PLAYER : submission.origin();
        if (origin == RequestOrigin.PLAYER && author.role() == MembershipRole.OBSERVER) {
            throw ApiException.forbidden(
                    "requests_not_for_observer", "Наблюдатель не предлагает правки в игру");
        }
        if (origin == RequestOrigin.MASTER && author.role() != MembershipRole.MASTER) {
            throw ApiException.forbidden("not_master", "Заявку от мастера подаёт только мастер");
        }

        String idempotencyKey = normalizeKey(submission.idempotencyKey());
        if (idempotencyKey != null) {
            Optional<GameRequest> existing = requests.findByIdempotencyKey(gameId, idempotencyKey);
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        List<ChangeSets.Planned> planned = List.of();
        String error = null;
        try {
            planned = ChangeSets.parse(submission.payload());
        } catch (ApiException invalid) {
            error = invalid.getMessage();
        }

        Instant now = Instant.now();
        GameRequest request = new GameRequest(
                Uuids.v7(),
                gameId,
                origin,
                submission.type(),
                author.id(),
                submission.toCharacterId(),
                submission.payload() == null ? Map.of() : submission.payload(),
                error == null ? RequestStatus.PENDING : RequestStatus.FAILED,
                error,
                idempotencyKey,
                now,
                now,
                submission.expiresAt() == null ? now.plus(defaultTtl) : submission.expiresAt());

        if (!requests.insert(request)) {
            return requests.findByIdempotencyKey(gameId, idempotencyKey)
                    .orElseThrow(() -> ApiException.conflict(
                            "request_exists", "Заявка с таким ключом уже подана"));
        }
        for (ChangeSets.Planned item : planned) {
            operations.insert(new RequestOperation(
                    Uuids.v7(),
                    request.id(),
                    item.ordinal(),
                    item.op(),
                    item.payload(),
                    OperationStatus.PENDING.name(),
                    null));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", request.id().toString());
        payload.put("origin", request.origin().name());
        payload.put("requestType", request.type().name());
        payload.put("status", request.status().name());
        if (request.error() != null) {
            payload.put("error", request.error());
        }
        mutations.appendEvent(
                gameId, GameEventType.REQUEST_SUBMITTED, authorUserId(request), payload);
        return requests.findById(gameId, request.id()).orElseThrow();
    }

    /**
     * Заявка модели заводится <em>до</em> вызова провайдера (`D-043`, `PLAN F05`): строка
     * со статусом `RUNNING` фиксирует ожидание в БД, поэтому перезапуск сервера не теряет
     * факт запроса, а мастер видит, что предложение готовится, а не что его нет.
     *
     * <p>Событие пишется здесь же и одно на всю заявку: `REQUEST_SUBMITTED` означает
     * «заявка заведена» (`D-134`), а не «модель ответила». Отдельного типа для ответа
     * модели словарь не содержит, и вводить его ради строки состояния значило бы расширять
     * контракт там, где хватает колонки `status`; решение мастера по-прежнему оставляет
     * `REQUEST_DECIDED`.
     *
     * <p>Повтор с тем же ключом возвращает прежнюю заявку и признак «уже была»: вызывающий
     * по нему пропускает вызов провайдера, а не платит за него второй раз.
     */
    @Transactional
    public LlmStart beginLlm(
            UUID actorUserId,
            UUID gameId,
            RequestType type,
            UUID toCharacterId,
            Map<String, Object> context,
            String idempotencyKey) {
        Membership master = requireMaster(actorUserId, gameId);
        if (type != RequestType.CO_MASTER && type != RequestType.AUTOPILOT) {
            throw ApiException.badRequest(
                    "invalid_request_type", "Модель отвечает на заявки CO_MASTER и AUTOPILOT");
        }
        String key = normalizeKey(idempotencyKey);
        if (key != null) {
            Optional<GameRequest> existing = requests.findByIdempotencyKey(gameId, key);
            if (existing.isPresent()) {
                return new LlmStart(existing.get(), false);
            }
        }
        Instant now = Instant.now();
        GameRequest request = new GameRequest(
                Uuids.v7(),
                gameId,
                RequestOrigin.LLM,
                type,
                master.id(),
                toCharacterId,
                context == null ? Map.of() : context,
                RequestStatus.RUNNING,
                null,
                key,
                now,
                now,
                now.plus(defaultTtl));
        if (!requests.insert(request)) {
            return new LlmStart(
                    requests.findByIdempotencyKey(gameId, key)
                            .orElseThrow(() -> ApiException.conflict(
                                    "request_exists", "Заявка с таким ключом уже подана")),
                    false);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", request.id().toString());
        payload.put("origin", RequestOrigin.LLM.name());
        payload.put("requestType", type.name());
        payload.put("status", RequestStatus.RUNNING.name());
        mutations.appendEvent(gameId, GameEventType.REQUEST_SUBMITTED, actorUserId, payload);
        return new LlmStart(requests.findById(gameId, request.id()).orElseThrow(), true);
    }

    /**
     * Ответ модели разобран и стал операциями (`D-043`). Невалидный change set не применяется
     * частично: заявка получает `FAILED` и код причины, а операции не появляются вовсе.
     *
     * <p>Ответ на уже закрытую заявку игнорируется: вызов мог завершиться после того, как
     * мастер отклонил ожидание, и перезаписывать решение нельзя.
     */
    @Transactional
    public GameRequest completeLlm(UUID gameId, UUID requestId, Map<String, Object> answer) {
        GameRequest request = find(gameId, requestId);
        if (request.status() != RequestStatus.RUNNING) {
            return request;
        }
        List<ChangeSets.Planned> planned;
        try {
            planned = ChangeSets.parse(answer);
        } catch (ApiException invalid) {
            return failLlm(gameId, requestId, "llm_invalid_response", invalid.getMessage());
        }
        for (ChangeSets.Planned item : planned) {
            operations.insert(new RequestOperation(
                    Uuids.v7(),
                    requestId,
                    item.ordinal(),
                    item.op(),
                    item.payload(),
                    OperationStatus.PENDING.name(),
                    null));
        }
        requests.updateStatus(gameId, requestId, RequestStatus.SUCCEEDED, null, Instant.now());
        return find(gameId, requestId);
    }

    /**
     * Отказ вызова модели — штатное состояние (`D-043`): заявка остаётся в истории со
     * статусом `FAILED` и машинным кодом причины, потому что лимит, таймаут и невалидный
     * ответ лечатся по-разному и не должны выглядеть одинаково.
     *
     * <p>Код и подробность разделены двоеточием: клиент переводит код по ключу
     * интернализации, а подробность показывает мастеру как есть.
     */
    @Transactional
    public GameRequest failLlm(UUID gameId, UUID requestId, String code, String detail) {
        GameRequest request = find(gameId, requestId);
        if (request.status() != RequestStatus.RUNNING) {
            return request;
        }
        String error = detail == null || detail.isBlank() ? code : code + ": " + detail;
        requests.updateStatus(gameId, requestId, RequestStatus.FAILED, error, Instant.now());
        return find(gameId, requestId);
    }

    /** Начало заявки модели: заявка и признак «строка создана этим вызовом». */
    public record LlmStart(GameRequest request, boolean created) {
    }

    /**
     * Решение мастера (`PLAN` E14, задача 3): названные операции применяются, остальные
     * отклоняются. Итог — `APPROVED`, `PARTIALLY_APPROVED`, `REJECTED` или `FAILED`, и он
     * не выводится из «сколько операций было», а считается по факту применения (`D-135`).
     *
     * <p>Порядок применения — по `ordinal`, откат — обратный: операции зависят друг от друга
     * (`create_item` кладёт предмет в хранилище, `move_character` требует персонажа на месте).
     */
    public GameRequest decide(UUID actorUserId, UUID gameId, UUID requestId, List<Integer> approve) {
        Membership master = requireMaster(actorUserId, gameId);
        GameRequest request = requireOpen(gameId, requestId);
        List<RequestOperation> all = operations.findByRequest(requestId);
        List<Integer> approved = approve == null ? List.of() : approve;

        int applied = 0;
        int rejected = 0;
        List<String> failures = new ArrayList<>();
        for (RequestOperation operation : all) {
            if (!approved.contains(operation.ordinal())) {
                operations.setStatus(operation.id(), OperationStatus.REJECTED, null);
                rejected++;
                continue;
            }
            try {
                Map<String, Object> oldValue = apply(master, request, operation);
                operations.setStatus(operation.id(), OperationStatus.APPLIED, oldValue);
                applied++;
            } catch (ApiException e) {
                // Одна упавшая операция — не повод ронять соседние применённые: `D-041`
                // прямо требует частичного результата. Причина названа с номером операции.
                operations.setStatus(operation.id(), OperationStatus.FAILED, null);
                failures.add(operation.ordinal() + ": " + e.getMessage());
            }
        }

        RequestStatus status;
        if (applied == 0) {
            status = failures.isEmpty() ? RequestStatus.REJECTED : RequestStatus.FAILED;
        } else if (failures.isEmpty() && rejected == 0) {
            status = RequestStatus.APPROVED;
        } else {
            status = RequestStatus.PARTIALLY_APPROVED;
        }
        String error = failures.isEmpty() ? null : String.join("; ", failures);
        requests.updateStatus(gameId, requestId, status, error, Instant.now());
        recordDecision(gameId, actorUserId, requestId, status, applied, rejected, error);
        return find(gameId, requestId);
    }

    /** Применение одной операции. Диспетчер, а не проверка: мир меняют мутации из одного горла. */
    private Map<String, Object> apply(Membership master, GameRequest request, RequestOperation operation) {
        Map<String, Object> payload = operation.payload();
        UUID requestId = request.id();
        UUID operationId = operation.id();
        UUID gameId = request.gameId();
        return switch (operation.op()) {
            case UPDATE_CHARACTER_FIELD -> mutations.applyCharacterFieldEdit(
                    master.userId(),
                    gameId,
                    ChangeSets.uuidOf(payload, "characterId"),
                    CharacterField.of(string(payload, "field")),
                    string(payload, "value"),
                    requestId,
                    operationId);
            case CREATE_ITEM -> {
                mutations.createItem(
                        master.userId(),
                        gameId,
                        enumOrNull(ItemType.class, string(payload, "type")),
                        string(payload, "name"),
                        string(payload, "description"),
                        null,
                        enumOf(ItemSize.class, string(payload, "size")),
                        number(payload, "quantity"),
                        string(payload, "properties"),
                        ChangeSets.uuidOf(payload, "storageId"));
                yield null;
            }
            case MOVE_CHARACTER -> mutations.applyCharacterMove(
                    master.userId(),
                    gameId,
                    ChangeSets.uuidOf(payload, "characterId"),
                    ChangeSets.uuidOf(payload, "transitionId"),
                    requestId,
                    operationId);
            case POST_MASTER_MESSAGE -> {
                messages.post(
                        master.userId(),
                        gameId,
                        ChangeSets.uuidOf(payload, "chatId"),
                        null,
                        MessageKind.MASTER,
                        string(payload, "body"),
                        false);
                yield null;
            }
            case UPDATE_STORAGE_CONTENTS -> mutations.applyStorageContentsEdit(
                    master.userId(),
                    gameId,
                    ChangeSets.uuidOf(payload, "storageId"),
                    string(payload, "title"),
                    string(payload, "description"),
                    requestId,
                    operationId);
            case CREATE_NOTE -> {
                notes.create(master.userId(), gameId, noteDraft(master, payload));
                yield null;
            }
            case POST_CHARACTER_MESSAGE -> {
                // Автопилот говорит за персонажа, а пишет мастер: `onBehalfOfMaster` — тот же
                // признак, что у мастера за NPC (`D-004`), поэтому лента не выдаёт автора
                // модели, а права проверяются как обычно (`D-065`).
                messages.post(
                        master.userId(),
                        gameId,
                        ChangeSets.uuidOf(payload, "chatId"),
                        ChangeSets.uuidOf(payload, "characterId"),
                        MessageKind.valueOf(string(payload, "kind")),
                        string(payload, "body"),
                        true);
                yield null;
            }
        };
    }

    private NoteService.Draft noteDraft(Membership master, Map<String, Object> payload) {
        SubjectKind kind = enumOrNull(SubjectKind.class, string(payload, "targetKind"));
        UUID targetId = ChangeSets.uuidOf(payload, "targetId");
        return new NoteService.Draft(
                kind == null ? SubjectKind.MEMBERSHIP : kind,
                targetId == null ? master.id() : targetId,
                string(payload, "body"),
                keywords(payload));
    }

    private static List<NoteService.Draft.Keyword> keywords(Map<String, Object> payload) {
        Object raw = payload.get("keywords");
        if (!(raw instanceof List<?> items)) {
            return List.of();
        }
        List<NoteService.Draft.Keyword> keywords = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Map<?, ?> map) {
                Object word = map.get("raw");
                if (word != null) {
                    keywords.add(new NoteService.Draft.Keyword(
                            word.toString(), Boolean.TRUE.equals(map.get("exactMatch"))));
                }
            }
        }
        return keywords;
    }

    /**
     * Откат применённого (`PLAN` E14, задача 3). Обратимые операции возвращают прежнее
     * значение, взятое из журнала; необратимые (`create_item`, `post_master_message`,
     * `create_note`) откатить нельзя — удаление запрещено (`D-010`), отзыв сообщений
     * запрещён (`D-008`), — и попытка названа ошибкой, а не тихим пропуском.
     *
     * <p>Откат идёт обратным порядком применения: операция зависела от предыдущей, и снимать
     * её надо первой.
     */
    public GameRequest rollback(UUID actorUserId, UUID gameId, UUID requestId, List<Integer> ordinals) {
        Membership master = requireMaster(actorUserId, gameId);
        // Статус заявки здесь не проверяется: откат — противоположность решению, и заявка
        // к этому моменту уже решена (`APPROVED` или `PARTIALLY_APPROVED`). Естественный
        // признак «откатывать нечего» — отсутствие применённых операций, и именно он даёт
        // причину отказа; проверка «заявка открыта» запрещала бы ровно то, ради чего откат есть.
        GameRequest request = find(gameId, requestId);
        List<RequestOperation> all = operations.findByRequest(requestId);
        List<Integer> targets = ordinals == null
                ? all.stream().map(RequestOperation::ordinal).toList()
                : ordinals;

        int rolledBack = 0;
        for (int index = all.size() - 1; index >= 0; index--) {
            RequestOperation operation = all.get(index);
            if (!targets.contains(operation.ordinal())
                    || operation.statusOf() != OperationStatus.APPLIED) {
                continue;
            }
            if (!operation.op().reversible()) {
                throw ApiException.conflict(
                        "operation_not_reversible",
                        "Операция " + operation.op().wireName() + " не откатывается: удаление запрещено");
            }
            undo(master, request, operation);
            operations.setStatus(operation.id(), OperationStatus.ROLLED_BACK, operation.oldValue());
            rolledBack++;
        }
        if (rolledBack == 0) {
            throw ApiException.conflict(
                    "nothing_to_roll_back", "В заявке нет применённых операций для отката");
        }

        boolean anyApplied = operations.findByRequest(requestId).stream()
                .anyMatch(operation -> operation.statusOf() == OperationStatus.APPLIED);
        RequestStatus status = anyApplied ? RequestStatus.PARTIALLY_APPROVED : RequestStatus.REJECTED;
        requests.updateStatus(gameId, requestId, status, "откат применённых операций", Instant.now());
        recordEvent(gameId, GameEventType.REQUEST_ROLLED_BACK, actorUserId, requestId, status, rolledBack, 0, null);
        return find(gameId, requestId);
    }

    private void undo(Membership master, GameRequest request, RequestOperation operation) {
        Map<String, Object> payload = operation.payload();
        Map<String, Object> old = oldValueFromJournal(request.gameId(), operation);
        switch (operation.op()) {
            case UPDATE_CHARACTER_FIELD -> mutations.applyCharacterFieldEdit(
                    master.userId(),
                    request.gameId(),
                    ChangeSets.uuidOf(payload, "characterId"),
                    CharacterField.of(string(old, "field")),
                    string(old, "value"),
                    request.id(),
                    operation.id());
            case MOVE_CHARACTER -> {
                UUID back = ChangeSets.uuidOf(old, "chatId");
                if (back == null) {
                    throw ApiException.conflict(
                            "rollback_without_value", "В журнале нет прежней локации персонажа");
                }
                mutations.applyCharacterPlacement(
                        master.userId(),
                        request.gameId(),
                        ChangeSets.uuidOf(payload, "characterId"),
                        back,
                        request.id(),
                        operation.id());
            }
            case UPDATE_STORAGE_CONTENTS -> mutations.applyStorageContentsEdit(
                    master.userId(),
                    request.gameId(),
                    ChangeSets.uuidOf(payload, "storageId"),
                    string(old, "title"),
                    string(old, "description"),
                    request.id(),
                    operation.id());
            case CREATE_ITEM, POST_MASTER_MESSAGE, CREATE_NOTE -> throw ApiException.conflict(
                    "operation_not_reversible",
                    "Операция " + operation.op().wireName() + " не откатывается: удаление запрещено");
        }
    }

    /**
     * Прежнее значение, снятое при применении (`D-138`). Читается из журнала, а не из
     * {@code request_operation.old_value}: колонка — кэш для панели мастера, и источником
     * истины остаётся журнал (`D-033`). Событие помечено `operationId`, поэтому поиск
     * точен, а не «последнее событие с таким типом».
     *
     * <p>Отсутствие события — не «пустое значение», а сломанная история: применить операцию
     * без записи события нельзя (мутации идут через одно горло, `D-034`), и откат обязан
     * сказать об этом, а не восстановить пустоту.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> oldValueFromJournal(UUID gameId, RequestOperation operation) {
        return events.findByOperationId(gameId, operation.id())
                .map(GameEvent::payload)
                .map(payload -> payload.get("oldValue"))
                .filter(Map.class::isInstance)
                .map(raw -> (Map<String, Object>) raw)
                .orElseThrow(() -> ApiException.conflict(
                        "rollback_without_value",
                        "В журнале нет прежнего значения операции " + operation.ordinal()));
    }

    /**
     * Автоотклонение просроченных (`PLAN` E14, задача 6). Прогон целиком — одна транзакция:
     * статус и событие по каждой заявке пишутся вместе, а иначе падение на середине оставило
     * бы заявку закрытой без следа в журнале.
     *
     * <p>К таймауту хода это отношения не имеет: `D-060` удалил таймаут <em>хода</em>, а здесь
     * истекает ожидание ответа мастеру, и мир по времени не меняется.
     */
    @Transactional
    public int expireDue() {
        List<GameRequest> expired = requests.findExpired(Instant.now());
        for (GameRequest request : expired) {
            expire(request);
        }
        return expired.size();
    }

    /** Просрочка одной заявки: статус, причина и событие — иначе отказ выглядел бы молчанием. */
    private void expire(GameRequest request) {
        requests.updateStatus(
                request.gameId(), request.id(), RequestStatus.EXPIRED, "истёк срок жизни", Instant.now());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", request.id().toString());
        payload.put("status", RequestStatus.EXPIRED.name());
        mutations.appendStandaloneEvent(
                request.gameId(), GameEventType.REQUEST_EXPIRED, authorUserId(request), payload);
    }

    /** Заявки игры: мастеру — все, участнику — только свои (`REQUIREMENTS §17`). */
    public List<GameRequest> list(UUID actorUserId, UUID gameId, RequestStatus status) {
        Membership member = requireParticipant(actorUserId, gameId);
        if (member.role() == MembershipRole.MASTER) {
            return requests.findByGame(gameId, status);
        }
        return requests.findByAuthor(gameId, member.id());
    }

    public GameRequest find(UUID gameId, UUID requestId) {
        return requests.findById(gameId, requestId)
                .orElseThrow(() -> ApiException.notFound("request_not_found", "Заявка не найдена"));
    }

    public List<RequestOperation> operationsOf(UUID requestId) {
        return operations.findByRequest(requestId);
    }

    private void recordDecision(
            UUID gameId,
            UUID actorUserId,
            UUID requestId,
            RequestStatus status,
            int applied,
            int rejected,
            String error) {
        recordEvent(
                gameId, GameEventType.REQUEST_DECIDED, actorUserId, requestId, status, applied, rejected, error);
    }

    /**
     * Факт истории — свой тип события, а не общий `REQUEST_DECIDED` с пометкой в payload
     * (`D-134`): по одному типу «по заявке решали» нельзя отличить «применили» от «вернули
     * применённое», а именно это и спрашивают у журнала при разборе правок.
     */
    private void recordEvent(
            UUID gameId,
            GameEventType type,
            UUID actorUserId,
            UUID requestId,
            RequestStatus status,
            int applied,
            int rejected,
            String error) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", requestId.toString());
        payload.put("status", status.name());
        payload.put("applied", applied);
        payload.put("rejected", rejected);
        if (error != null) {
            payload.put("error", error);
        }
        // Своё событие, а не событие внутри чужой транзакции: решение и откат идут вне
        // транзакции заявки — применение каждой операции открывает собственную (`D-137`).
        mutations.appendStandaloneEvent(gameId, type, actorUserId, payload);
    }

    /**
     * Автор заявки как учётная запись: `game_event.actor_user_id` ссылается на человека, а
     * заявка помнит участие. Переводится здесь, а не в момент подачи, потому что участие
     * и есть то, что видит игра, — а человек нужен только журналу.
     */
    private UUID authorUserId(GameRequest request) {
        if (request.fromMembershipId() == null) {
            return null;
        }
        return memberships.findById(request.fromMembershipId())
                .map(Membership::userId)
                .orElse(null);
    }

    private Membership requireParticipant(UUID userId, UUID gameId) {
        Membership membership = memberships.find(gameId, userId)
                .orElseThrow(() -> ApiException.forbidden("not_a_member", "Нет доступа к этой игре"));
        if (membership.banned()) {
            throw ApiException.forbidden("membership_banned", "Участие в этой игре запрещено мастером");
        }
        if (!membership.joined()) {
            throw ApiException.forbidden("membership_not_joined", "Участие в этой игре не активно");
        }
        return membership;
    }

    private Membership requireMaster(UUID userId, UUID gameId) {
        Membership membership = requireParticipant(userId, gameId);
        if (membership.role() != MembershipRole.MASTER) {
            throw ApiException.forbidden("not_master", "Решает по заявке только мастер");
        }
        return membership;
    }

    private GameRequest requireOpen(UUID gameId, UUID requestId) {
        GameRequest request = find(gameId, requestId);
        if (request.status() == RequestStatus.RUNNING) {
            // Модель ещё отвечает: операции появятся в конце вызова, и решение сейчас
            // записало бы итог по пустому набору (`D-043`).
            throw ApiException.conflict(
                    "request_running", "Модель ещё готовит предложение: дождитесь ответа");
        }
        if (!request.status().awaitingDecision()) {
            throw ApiException.conflict(
                    "request_closed", "По заявке уже принято решение: " + request.status());
        }
        if (request.expiredAt(Instant.now())) {
            expire(request);
            throw ApiException.conflict("request_expired", "Срок жизни заявки истёк");
        }
        return request;
    }

    private static String normalizeKey(String key) {
        return key == null || key.isBlank() ? null : key.strip();
    }

    private static String string(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? null : value.toString();
    }

    private static Integer number(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value instanceof Number number ? number.intValue() : null;
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest(
                    "invalid_change_set", "Не указано значение " + type.getSimpleName());
        }
        try {
            return Enum.valueOf(type, raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(
                    "invalid_change_set", "Неизвестное значение " + type.getSimpleName() + ": " + raw);
        }
    }

    private static <E extends Enum<E>> E enumOrNull(Class<E> type, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(
                    "invalid_change_set", "Неизвестное значение " + type.getSimpleName() + ": " + raw);
        }
    }

    /**
     * Подача заявки. Отдельный тип, а не десяток параметров: часть полей необязательна
     * (`idempotencyKey`, `expiresAt`), и позиционный вызов с ними читался бы шиворот-навыворот.
     */
    public record Submission(
            RequestOrigin origin,
            RequestType type,
            UUID toCharacterId,
            Map<String, Object> payload,
            String idempotencyKey,
            Instant expiresAt) {
    }
}
