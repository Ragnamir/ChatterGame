package com.chattergame.web.message;

import com.chattergame.game.GameMutationService;
import com.chattergame.game.GameQueryService;
import com.chattergame.message.Message;
import com.chattergame.message.MessageQueryService;
import com.chattergame.message.MessageService;
import com.chattergame.restriction.Restriction;
import com.chattergame.web.AuthContext;
import com.chattergame.web.AuthContextResolver;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Сообщения, лента и ограничения (`PLAN` E08).
 *
 * <p>Запись идёт через {@link MessageService}, и это не формальность: сначала право,
 * потом эффект, потом событие и строки `inbox_entry` — всё в одной транзакции (`D-034`).
 * Контроллер не касается доменных таблиц вовсе.
 *
 * <p>Чтение — через {@link MessageQueryService}: чей лентой смотреть, решает параметр
 * `asCharacter`, а не соединение. Наблюдатель читает участием, потому что персонажа
 * у него нет (`D-086`).
 */
@RestController
@RequestMapping("/games/{gameId}")
public class MessageController {

    private final MessageService writes;
    private final MessageQueryService reads;
    private final GameMutationService mutations;
    private final GameQueryService queries;

    public MessageController(
            MessageService writes,
            MessageQueryService reads,
            GameMutationService mutations,
            GameQueryService queries) {
        this.writes = writes;
        this.reads = reads;
        this.mutations = mutations;
        this.queries = queries;
    }

    // --- Сообщения -------------------------------------------------------------------

    @PostMapping("/chats/{chatId}/messages")
    public ResponseEntity<MessageView> post(
            @PathVariable UUID gameId,
            @PathVariable UUID chatId,
            @Valid @RequestBody PostMessageRequest request,
            AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        Message message = writes.post(
                auth.user().id(),
                gameId,
                chatId,
                request.characterId(),
                request.kind(),
                request.body(),
                request.onBehalfOfMaster());
        return ResponseEntity.status(HttpStatus.CREATED).body(MessageView.of(
                com.chattergame.message.FeedItem.of(message)));
    }

    /**
     * Лента чата глазами указанного персонажа. `afterSeq` — подтверждённый клиентом номер
     * доставки, а не «с чего начать»: resume и догрузка идут одним запросом (`D-083`).
     */
    @GetMapping("/chats/{chatId}/messages")
    public FeedView feed(
            @PathVariable UUID gameId,
            @PathVariable UUID chatId,
            @RequestParam(required = false) UUID asCharacter,
            @RequestParam(defaultValue = "0") long afterSeq,
            @RequestParam(defaultValue = "0") int limit,
            AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        return FeedView.of(reads.feed(auth.user().id(), gameId, chatId, asCharacter, afterSeq, limit));
    }

    /**
     * Вход в локацию (`D-032`): курсор на текущий максимум плюс отдельная выдача последней
     * реплики. Всё, что было до входа, не вываливается простыней.
     */
    @PostMapping("/chats/{chatId}/enter")
    public Map<String, Object> enter(
            @PathVariable UUID gameId,
            @PathVariable UUID chatId,
            @RequestParam(required = false) UUID asCharacter,
            AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        return writes.enterChat(auth.user().id(), gameId, chatId, asCharacter);
    }

    /** Отметка «прочитано до». Пустое тело означает «до конца чата» (`REQUIREMENTS §12`). */
    @PostMapping("/chats/{chatId}/read")
    public Map<String, Object> markRead(
            @PathVariable UUID gameId,
            @PathVariable UUID chatId,
            @RequestParam(required = false) UUID asCharacter,
            @RequestBody(required = false) ReadRequest request,
            AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        long seq = request == null || request.seq() == null ? 0L : request.seq();
        return writes.markRead(auth.user().id(), gameId, chatId, asCharacter, seq);
    }

    /** Счётчики непрочитанного выводятся из `inbox_entry` (`D-036`), а не из счётчика в сокете. */
    @GetMapping("/unread")
    public Map<String, Object> unread(
            @PathVariable UUID gameId,
            @RequestParam(required = false) UUID asCharacter,
            AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        var rows = reads.unread(auth.user().id(), gameId, asCharacter);
        return Map.of(
                "total", rows.stream().mapToLong(r -> r.count()).sum(),
                "chats", rows.stream()
                        .map(r -> Map.of("chatId", r.chatId().toString(), "count", r.count()))
                        .toList());
    }

    /** Курсоры субъекта по чатам; их же клиент продолжает из `last_seq` при resume (`D-083`). */
    @GetMapping("/cursors")
    public Map<String, Long> cursors(
            @PathVariable UUID gameId,
            @RequestParam(required = false) UUID asCharacter,
            AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        return reads.cursors(auth.user().id(), gameId, asCharacter);
    }

    // --- Ограничения -----------------------------------------------------------------

    /**
     * Навешивание запрета (`REQUIREMENTS §13`, `D-064`). Субъект диктуется видом запрета:
     * `NO_JOKE` — участие, остальные — персонаж; это проверяет сервис, а не клиент.
     */
    @PostMapping("/restrictions")
    public ResponseEntity<RestrictionView> restrict(
            @PathVariable UUID gameId,
            @Valid @RequestBody RestrictionRequest request,
            AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        Restriction restriction = mutations.setRestriction(
                auth.user().id(),
                gameId,
                request.subjectKind(),
                request.subjectId(),
                request.kind(),
                request.reason(),
                request.expiresAt());
        return ResponseEntity.status(HttpStatus.CREATED).body(RestrictionView.of(restriction));
    }

    /** Снятие — штатная операция, а не удаление: строка остаётся частью истории (`D-064`). */
    @PostMapping("/restrictions/{restrictionId}/revoke")
    public RestrictionView revoke(
            @PathVariable UUID gameId, @PathVariable UUID restrictionId, AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        return RestrictionView.of(mutations.revokeRestriction(auth.user().id(), gameId, restrictionId));
    }

    @GetMapping("/restrictions")
    public List<RestrictionView> restrictions(@PathVariable UUID gameId, AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        return queries.restrictions(auth.user().id(), gameId).stream()
                .map(RestrictionView::of)
                .toList();
    }
}
