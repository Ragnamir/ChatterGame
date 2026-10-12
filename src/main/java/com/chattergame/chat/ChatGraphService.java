package com.chattergame.chat;

import com.chattergame.character.Character;
import com.chattergame.character.CharacterQueryService;
import com.chattergame.character.CharacterRepository;
import com.chattergame.character.PresentCharacter;
import com.chattergame.game.Game;
import com.chattergame.game.Membership;
import com.chattergame.game.MembershipRepository;
import com.chattergame.game.MembershipRole;
import com.chattergame.game.Readiness;
import com.chattergame.util.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Сборка графа локаций для клиента (`PLAN` E04, задача 6). Отдельно от
 * {@code GameMutationService}, потому что здесь нет записи: только проекция.
 *
 * <p>Правило видимости применяется один раз, при сборке ответа: рёбра отбираются по знанию
 * конкретного персонажа, а архивация локации показывается по-разному до и после `READY`
 * (`D-009`). Разложи это по запросам — и первое же изменение правила даст утечку, как и с
 * `inbox_entry` (`D-030`).
 */
@Service
public class ChatGraphService {

    private final ChatRepository chats;
    private final TransitionRepository transitions;
    private final TransitionStateRepository edgeStates;
    private final CharacterRepository characters;
    private final CharacterQueryService characterReads;
    private final MembershipRepository memberships;

    public ChatGraphService(
            ChatRepository chats,
            TransitionRepository transitions,
            TransitionStateRepository edgeStates,
            CharacterRepository characters,
            CharacterQueryService characterReads,
            MembershipRepository memberships) {
        this.chats = chats;
        this.transitions = transitions;
        this.edgeStates = edgeStates;
        this.characters = characters;
        this.characterReads = characterReads;
        this.memberships = memberships;
    }

    /**
     * Граф глазами запросившего. Без персонажа смотрит только мастер — ему отдаются все рёбра
     * вместе с архивными, потому что он ими управляет. С персонажем отбираются рёбра, известные
     * этому персонажу (`REQUIREMENTS §8`): мастер так может посмотреть глазами игрока, а игрок
     * обязан смотреть только своим персонажем, иначе узнал бы чужое знание.
     */
    @Transactional(readOnly = true)
    public GameGraph graphFor(Game game, UUID userId, UUID characterId) {
        Membership membership = memberships.find(game.id(), userId)
                .orElseThrow(() -> ApiException.forbidden("not_a_member", "Нет доступа к этой игре"));
        if (membership.banned()) {
            throw ApiException.forbidden("membership_banned", "Участие в этой игре запрещено мастером");
        }
        boolean master = membership.role() == MembershipRole.MASTER;
        UUID viewAs = authorizeViewer(game, membership, master, characterId);

        List<Chat> locations = chats.findByGame(game.id(), ChatKind.LOCATION);
        Set<UUID> archived = locations.stream()
                .filter(Chat::archived)
                .map(Chat::id)
                .collect(Collectors.toSet());

        List<GameGraph.Node> nodes = new ArrayList<>();
        // Присутствующие собираются одним запросом на игру: граф отдаётся целиком, и запрос
        // на каждую локацию отдельно превратил бы один ответ клиенту в полсотни запросов к базе.
        Map<UUID, List<PresentCharacter>> present = characterReads.presentByChat(game.id(), master, viewAs);
        for (Chat location : locations) {
            // До READY архивная локация для игрока не существует вовсе: он не должен узнать,
            // что мастер что-то заводил и передумал (`D-009`).
            if (location.archived() && !master && game.state().readiness() != Readiness.READY) {
                continue;
            }
            nodes.add(new GameGraph.Node(
                    location.id(),
                    location.title(),
                    location.description(),
                    location.archived(),
                    location.archivedReason(),
                    present.getOrDefault(location.id(), List.of())));
        }

        List<GameGraph.Edge> edges = new ArrayList<>();
        if (viewAs == null) {
            for (Transition transition : transitions.findByGame(game.id())) {
                edges.add(new GameGraph.Edge(
                        transition.id(),
                        transition.fromChatId(),
                        transition.toChatId(),
                        transition.title(),
                        transition.archived(),
                        transition.archivedReason(),
                        null,
                        null));
            }
        } else {
            Map<UUID, TransitionState> known = edgeStates.findByCharacter(viewAs).stream()
                    .collect(Collectors.toMap(TransitionState::transitionId, Function.identity()));
            for (Transition transition : transitions.findByGame(game.id())) {
                // Ребро в архивную локацию игроку не отдаётся даже как «недоступное»: место
                // выведено из игры, и перехода туда для него не существует (`D-009`).
                if (archived.contains(transition.toChatId()) || archived.contains(transition.fromChatId())) {
                    continue;
                }
                TransitionState state = known.get(transition.id());
                if (state == null || state.state() == EdgeState.UNKNOWN) {
                    continue;
                }
                edges.add(new GameGraph.Edge(
                        transition.id(),
                        transition.fromChatId(),
                        transition.toChatId(),
                        transition.title(),
                        false,
                        null,
                        state.state().name(),
                        state.reason()));
            }
        }

        return new GameGraph(nodes, edges);
    }

    /**
     * Проверка «чьими глазами» и ответ на вопрос, отбирать ли рёбра по знанию персонажа.
     * Мастер без персонажа получает полный граф, поэтому `null` здесь — это не «нет доступа»,
     * а «смотрит как мастер».
     */
    private UUID authorizeViewer(Game game, Membership membership, boolean master, UUID characterId) {
        if (characterId == null) {
            if (!master) {
                throw ApiException.badRequest(
                        "character_required", "Укажите персонажа, от чьего лица смотрите граф");
            }
            return null;
        }

        Character character = characters.findById(characterId)
                .orElseThrow(() -> ApiException.notFound("character_not_found", "Персонаж не найден"));
        if (!character.gameId().equals(game.id())) {
            throw ApiException.notFound("character_not_found", "Персонаж не найден");
        }
        // Владение персонажем читается, но не пишется на E04: занятие и освобождение — E06.
        if (!master && !membership.id().equals(character.occupantMembershipId())) {
            throw ApiException.forbidden("not_your_character", "Этот персонаж не за вами");
        }
        return characterId;
    }
}
