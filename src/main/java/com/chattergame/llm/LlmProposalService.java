package com.chattergame.llm;

import com.chattergame.character.Character;
import com.chattergame.character.CharacterRepository;
import com.chattergame.game.Game;
import com.chattergame.game.GameRepository;
import com.chattergame.game.Membership;
import com.chattergame.game.MembershipRepository;
import com.chattergame.game.MembershipRole;
import com.chattergame.note.Note;
import com.chattergame.note.NoteService;
import com.chattergame.request.GameRequest;
import com.chattergame.request.RequestService;
import com.chattergame.request.RequestType;
import com.chattergame.util.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Вызов модели и превращение её ответа в заявку (`PLAN F05`, `REQUIREMENTS §18`).
 *
 * <p>Порядок здесь важнее кода: сначала заявка со статусом `RUNNING` фиксируется в БД, потом
 * идёт вызов провайдера, потом результат разбирается и становится операциями. Так мастер
 * видит ожидание, а перезапуск сервера не теряет запрос (`D-043`). Вызов не обёрнут
 * транзакцией (`D-044`): он идёт десятки секунд и выбрал бы пул соединений до нуля.
 *
 * <p>Отказ модели не бросается наружу: заявка получает `FAILED` и машинный код причины
 * (`llm_rate_limited`, `llm_timeout`, `llm_invalid_response`, …), потому что «кончились
 * лимиты» и «модель ответила мусором» лечатся по-разному и не должны выглядеть одинаково.
 * Исключение — занятость игры: это не ответ модели, и клиенту нужен честный `409`.
 */
@Service
public class LlmProposalService {

    private static final int TRANSCRIPT_LIMIT = 40;
    private static final int CHARACTERS_LIMIT = 40;
    private static final int NOTES_LIMIT = 20;

    private final GameRepository games;
    private final MembershipRepository memberships;
    private final CharacterRepository characters;
    private final LlmCredentialService credentials;
    private final LlmProvider provider;
    private final LlmCallExecutor executor;
    private final LlmContextRepository context;
    private final LlmJson json;
    private final NoteService notes;
    private final RequestService requests;
    private final Duration timeout;

    public LlmProposalService(
            GameRepository games,
            MembershipRepository memberships,
            CharacterRepository characters,
            LlmCredentialService credentials,
            LlmProvider provider,
            LlmCallExecutor executor,
            LlmContextRepository context,
            LlmJson json,
            NoteService notes,
            RequestService requests,
            @Value("${chattergame.llm.timeout:PT60S}") Duration timeout) {
        this.games = games;
        this.memberships = memberships;
        this.characters = characters;
        this.credentials = credentials;
        this.provider = provider;
        this.executor = executor;
        this.context = context;
        this.json = json;
        this.notes = notes;
        this.requests = requests;
        this.timeout = timeout;
    }

    /**
     * Доступны ли опции LLM: без активного ключа интерфейс их не показывает (`D-042`).
     * Спрашивать может только мастер: наличие чужого ключа — не публичная информация.
     */
    public boolean available(UUID userId, UUID gameId) {
        requireMaster(userId, gameId);
        return credentials.active(userId).isPresent();
    }

    /**
     * Предложение модели. Возвращает заявку в любом исходе: успешную, ожидающую (`RUNNING`,
     * если вызов ещё идёт в другом потоке) или `FAILED` с кодом причины.
     */
    public GameRequest propose(UUID userId, UUID gameId, Proposal proposal) {
        requireMaster(userId, gameId);
        Game game = games.findById(gameId)
                .orElseThrow(() -> ApiException.notFound("game_not_found", "Игра не найдена"));
        LlmCredentialService.ActiveCredential credential = credentials.active(userId)
                .orElseThrow(() -> ApiException.conflict(
                        "llm_not_configured", "Ключ LLM не задан: опции модели недоступны"));

        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("instruction", proposal.instruction());
        if (proposal.chatId() != null) {
            stored.put("chatId", proposal.chatId().toString());
        }
        if (proposal.characterId() != null) {
            stored.put("characterId", proposal.characterId().toString());
        }

        RequestService.LlmStart start = requests.beginLlm(
                userId,
                gameId,
                proposal.type(),
                proposal.characterId(),
                stored,
                proposal.idempotencyKey());
        if (!start.created()) {
            return start.request();
        }

        String system = LlmPrompts.system(proposal.type());
        String user = LlmPrompts.user(
                proposal.type(),
                proposal.instruction(),
                game.title(),
                game.state().phase().name(),
                characterLines(gameId, proposal),
                noteLines(userId, gameId, proposal),
                transcriptLines(gameId, proposal));

        String answer;
        try {
            answer = executor.call(
                    gameId,
                    timeout.toMillis(),
                    () -> provider.complete(new LlmProvider.Request(
                            credential.baseUrl(),
                            credential.model(),
                            credential.secret(),
                            system,
                            user,
                            timeout)));
        } catch (LlmCallException failure) {
            return requests.failLlm(gameId, start.request().id(), failure.code(), failure.getMessage());
        } catch (ApiException busy) {
            // Занятость игры и переполнение очереди — не ответ модели: строку закрываем,
            // чтобы она не висела в `RUNNING`, а клиенту отдаём честный статус.
            requests.failLlm(gameId, start.request().id(), busy.code(), busy.getMessage());
            throw busy;
        }

        Map<String, Object> parsed;
        try {
            parsed = json.object(answer);
        } catch (LlmCallException invalid) {
            return requests.failLlm(gameId, start.request().id(), invalid.code(), invalid.getMessage());
        }
        return requests.completeLlm(gameId, start.request().id(), parsed);
    }

    /**
     * Сводка персонажей. Закрытая история чужого персонажа не отдаётся (`D-046`): со-мастер
     * видит карточку как все, автопилот — ещё и свою закрытую историю, потому что играет
     * этого персонажа.
     */
    private List<String> characterLines(UUID gameId, Proposal proposal) {
        boolean autopilot = proposal.type() == RequestType.AUTOPILOT;
        List<String> lines = new ArrayList<>();
        for (Character character : characters.findByGame(gameId)) {
            if (character.archived() || lines.size() >= CHARACTERS_LIMIT) {
                continue;
            }
            boolean own = autopilot && character.id().equals(proposal.characterId());
            StringBuilder line = new StringBuilder();
            line.append(character.name()).append(" (id ").append(character.id()).append(')');
            if (character.npc()) {
                line.append(", NPC");
            }
            if (character.appearance() != null && !character.appearance().isBlank()) {
                line.append("; внешность: ").append(character.appearance().strip());
            }
            if (character.publicHistory() != null && !character.publicHistory().isBlank()) {
                line.append("; открытая история: ").append(character.publicHistory().strip());
            }
            if (own && character.privateHistory() != null && !character.privateHistory().isBlank()) {
                line.append("; своя закрытая история: ").append(character.privateHistory().strip());
            }
            lines.add(line.toString());
        }
        return lines;
    }

    /**
     * Заметки: автопилот получает заметки своего персонажа (`D-046`), со-мастер — свои
     * мастерские. Заметки других игроков не отдаются: это их знание, а не текст мира.
     */
    private List<String> noteLines(UUID userId, UUID gameId, Proposal proposal) {
        List<String> lines = new ArrayList<>();
        for (Note.WithKeywords entry : notes.visible(userId, gameId)) {
            if (lines.size() >= NOTES_LIMIT) {
                break;
            }
            Note note = entry.note();
            boolean own = proposal.type() == RequestType.AUTOPILOT
                    ? note.target().id().equals(proposal.characterId())
                    : note.authorMembershipId() != null
                            && note.authorMembershipId().equals(membershipId(userId, gameId));
            if (own) {
                lines.add(note.body().strip());
            }
        }
        return lines;
    }

    private UUID membershipId(UUID userId, UUID gameId) {
        return memberships.find(gameId, userId).map(Membership::id).orElse(null);
    }

    private List<String> transcriptLines(UUID gameId, Proposal proposal) {
        List<LlmContextRepository.Line> lines = proposal.chatId() == null
                ? context.recent(gameId, TRANSCRIPT_LIMIT)
                : context.recentInChat(gameId, proposal.chatId(), TRANSCRIPT_LIMIT);
        List<String> rendered = new ArrayList<>(lines.size());
        for (LlmContextRepository.Line line : lines) {
            rendered.add(render(line));
        }
        return rendered;
    }

    private static String render(LlmContextRepository.Line line) {
        String who = line.speaker() == null || line.speaker().isBlank() ? "мастер" : line.speaker();
        String body = line.body() == null ? "" : line.body().strip();
        return "[" + line.chatTitle() + "] " + who + ": " + body;
    }

    private Membership requireMaster(UUID userId, UUID gameId) {
        Membership membership = memberships.find(gameId, userId)
                .orElseThrow(() -> ApiException.forbidden("not_a_member", "Нет доступа к этой игре"));
        if (membership.role() != MembershipRole.MASTER) {
            throw ApiException.forbidden("not_master", "Модель вызывает только мастер");
        }
        if (membership.banned() || !membership.joined()) {
            throw ApiException.forbidden("membership_not_joined", "Участие в этой игре не активно");
        }
        return membership;
    }

    /** Что просят у модели: вид вызова, запрос мастера, сцена и персонаж. */
    public record Proposal(
            RequestType type,
            String instruction,
            UUID chatId,
            UUID characterId,
            String idempotencyKey) {

        public Proposal {
            if (instruction == null || instruction.isBlank()) {
                throw ApiException.badRequest("llm_instruction_required", "Опишите, что нужно от модели");
            }
            if (instruction.strip().length() > 4000) {
                throw ApiException.badRequest("llm_instruction_too_long", "Запрос к модели — до 4000 знаков");
            }
            if (type == RequestType.AUTOPILOT && (characterId == null || chatId == null)) {
                throw ApiException.badRequest(
                        "autopilot_target_required", "Автопилоту нужны персонаж и чат");
            }
        }
    }
}
