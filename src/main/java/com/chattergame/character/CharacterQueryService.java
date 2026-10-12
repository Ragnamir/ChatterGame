package com.chattergame.character;

import com.chattergame.game.Game;
import com.chattergame.game.GameRepository;
import com.chattergame.game.Membership;
import com.chattergame.game.MembershipRepository;
import com.chattergame.game.MembershipRole;
import com.chattergame.game.Readiness;
import com.chattergame.util.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Чтение персонажей (`PLAN` E06, задачи 2–5). Правило видимости применяется здесь и один
 * раз: и карточка, и списки присутствующих в графе смотрят через этот сервис, поэтому
 * «скрытый не виден» и «закрытая история только своему игроку» живут в одном месте,
 * а не расходятся по запросам (`D-030`).
 *
 * <p>Архивация показывается по-разному до и после `READY`, как и у локаций: до старта игрок
 * не должен узнать, что мастер кого-то заводил и передумал, после — «выведен из игры»
 * с причиной (`D-009`).
 */
@Service
public class CharacterQueryService {

    private final CharacterRepository characters;
    private final PresenceRepository presence;
    private final GameRepository games;
    private final MembershipRepository memberships;

    public CharacterQueryService(
            CharacterRepository characters,
            PresenceRepository presence,
            GameRepository games,
            MembershipRepository memberships) {
        this.characters = characters;
        this.presence = presence;
        this.games = games;
        this.memberships = memberships;
    }

    /** Карточки игры глазами читающего. Скрытые и архивные отсеиваются для не-мастера. */
    @Transactional(readOnly = true)
    public List<VisibleCharacter> list(UUID userId, UUID gameId) {
        View view = view(userId, gameId);
        List<VisibleCharacter> result = new ArrayList<>();
        for (Character character : characters.findByGame(gameId)) {
            VisibleCharacter visible = present(character, view);
            if (visible != null) {
                result.add(visible);
            }
        }
        result.sort(Comparator.comparing(VisibleCharacter::name));
        return result;
    }

    /** Карточка одного персонажа. Чужой отдаётся как отсутствующий, чтобы не выдать его существование. */
    @Transactional(readOnly = true)
    public VisibleCharacter card(UUID userId, UUID gameId, UUID characterId) {
        View view = view(userId, gameId);
        Character character = characters.findById(characterId)
                .orElseThrow(() -> ApiException.notFound("character_not_found", "Персонаж не найден"));
        if (!character.gameId().equals(gameId)) {
            throw ApiException.notFound("character_not_found", "Персонаж не найден");
        }
        VisibleCharacter visible = present(character, view);
        if (visible == null) {
            throw ApiException.notFound("character_not_found", "Персонаж не найден");
        }
        return visible;
    }

    /**
     * Присутствующие по локациям: проекция для узлов графа. Скрытый персонаж не попадает
     * в список никому, кроме мастера и самого смотрящего: «скрыт» означает «не виден
     * остальным», и собственный персонаж не должен пропасть из своей же картины мира
     * (`REQUIREMENTS §7.1`, `D-007`). Архивный не попадает никому: он больше не в игре.
     */
    @Transactional(readOnly = true)
    public Map<UUID, List<PresentCharacter>> presentByChat(
            UUID gameId, boolean master, UUID viewerCharacterId) {
        Map<UUID, Character> byId = new HashMap<>();
        for (Character character : characters.findByGame(gameId)) {
            if (!character.archived()) {
                byId.put(character.id(), character);
            }
        }
        Map<UUID, List<PresentCharacter>> byChat = new HashMap<>();
        for (Presence placement : presence.findActiveByGame(gameId)) {
            Character character = byId.get(placement.characterId());
            boolean mine = placement.characterId().equals(viewerCharacterId);
            if (character == null || (placement.hidden() && !master && !mine)) {
                continue;
            }
            byChat.computeIfAbsent(placement.chatId(), key -> new ArrayList<>())
                    .add(new PresentCharacter(
                            character.id(),
                            character.name(),
                            character.portraitAssetId(),
                            // Чужому игроку скрытность не видна уже потому, что персонажа
                            // нет в списке; видеть флаг «скрыт» может мастер и владелец.
                            placement.hidden() && (master || mine)));
        }
        byChat.values().forEach(people -> people.sort(Comparator.comparing(PresentCharacter::name)));
        return byChat;
    }

    /**
     * Мастер игры — единственная роль, которой доступно всё содержимое карточки. Проверка
     * читающего отдельная от {@code requireReadable}: участие нужно при любом чтении,
     * а роль меняет только объём полей.
     */
    private View view(UUID userId, UUID gameId) {
        Game game = games.findById(gameId)
                .orElseThrow(() -> ApiException.notFound("game_not_found", "Игра не найдена"));
        Membership membership = memberships.find(gameId, userId)
                .orElseThrow(() -> ApiException.forbidden("not_a_member", "Нет доступа к этой игре"));
        if (membership.banned()) {
            throw ApiException.forbidden("membership_banned", "Участие в этой игре запрещено мастером");
        }
        return new View(
                membership.role() == MembershipRole.MASTER,
                membership.id(),
                game.state().readiness() == Readiness.READY);
    }

    /** Проекция или `null`: невидимое не «приходит помеченным», а не приходит. */
    private VisibleCharacter present(Character character, View view) {
        if (character.archived() && !view.master() && !view.ready()) {
            return null;
        }
        Presence placement = presence.findActive(character.id()).orElse(null);
        boolean hidden = placement != null && placement.hidden();
        if (hidden && !view.master()) {
            return null;
        }
        boolean mine = character.occupantMembershipId() != null
                && character.occupantMembershipId().equals(view.membershipId());
        // Закрытая история — только играющему игроку и мастеру (`REQUIREMENTS §9`).
        String privateHistory = view.master() || mine ? character.privateHistory() : null;
        return new VisibleCharacter(
                character.id(),
                character.gameId(),
                character.name(),
                character.portraitAssetId(),
                character.appearance(),
                character.publicHistory(),
                privateHistory,
                character.playable(),
                character.npc(),
                character.archived(),
                character.archivedReason(),
                hidden,
                mine);
    }

    private record View(boolean master, UUID membershipId, boolean ready) {
    }

    /**
     * Карточка в ответе API. `privateHistory` заполнен не всегда — это и есть материализованное
     * правило видимости, а не отсутствие данных у клиента.
     */
    public record VisibleCharacter(
            UUID id,
            UUID gameId,
            String name,
            UUID portraitAssetId,
            String appearance,
            String publicHistory,
            String privateHistory,
            boolean playable,
            boolean npc,
            boolean archived,
            String archivedReason,
            boolean hidden,
            boolean mine) {
    }
}
