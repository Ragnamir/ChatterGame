package com.chattergame.llm.web;

import com.chattergame.llm.LlmProposalService;
import com.chattergame.request.GameRequest;
import com.chattergame.request.RequestType;
import com.chattergame.util.ApiException;
import com.chattergame.web.AuthContext;
import com.chattergame.web.AuthContextResolver;
import com.chattergame.web.request.RequestView;
import com.chattergame.web.request.RequestViews;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.UUID;

/**
 * Опции модели для мастера (`PLAN F05`, `REQUIREMENTS §18`).
 *
 * <p>Это не второй вход заявок: заявка по-прежнему одна и живёт в `RequestController`
 * (`D-040`). Здесь вызывается модель, и её ответ становится заявкой с `origin = LLM` —
 * ровно затем, чтобы мастер одобрял предложение тем же движком, что и просьбу игрока.
 *
 * <p>`GET` отвечает, доступны ли опции: без активного ключа интерфейс их не показывает
 * вовсе, а не показывает кнопку, которая падает (`D-042`).
 */
@RestController
@RequestMapping("/games/{gameId}/llm")
public class LlmController {

    private final LlmProposalService proposals;
    private final RequestViews views;

    public LlmController(LlmProposalService proposals, RequestViews views) {
        this.proposals = proposals;
        this.views = views;
    }

    @GetMapping
    public LlmStatusView status(@PathVariable UUID gameId, AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        boolean available = proposals.available(auth.user().id(), gameId);
        return new LlmStatusView(available, available ? null : "llm_not_configured");
    }

    /**
     * Вызов модели. Отказ провайдера возвращается как заявка `FAILED` с кодом причины, а не
     * как ошибка HTTP: это штатное состояние, и мастер видит его в очереди (`D-043`).
     */
    @PostMapping("/proposals")
    public RequestView propose(
            @PathVariable UUID gameId,
            @Valid @RequestBody LlmProposalRequest request,
            AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        GameRequest proposal = proposals.propose(
                auth.user().id(),
                gameId,
                new LlmProposalService.Proposal(
                        type(request.type()),
                        request.instruction(),
                        request.chatId(),
                        request.characterId(),
                        request.idempotencyKey()));
        return views.view(gameId, proposal);
    }

    private static RequestType type(String raw) {
        if (raw == null || raw.isBlank()) {
            return RequestType.CO_MASTER;
        }
        try {
            return RequestType.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(
                    "unknown_request_type", "Модель отвечает на CO_MASTER и AUTOPILOT");
        }
    }
}
