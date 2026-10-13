package com.chattergame.game;

import com.chattergame.journal.GameEvent;
import com.chattergame.journal.GameEventRepository;
import com.chattergame.journal.GameEventType;
import com.chattergame.util.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Чтение игр и журнала. Отдельно от {@link GameMutationService} потому, что проверки прав
 * у чтения и записи разные: наблюдатель читает, но не меняет (`D-063`), а смешение этих
 * двух наборов условий в одном классе — верный способ выдать право на запись тому, кто
 * просил только посмотреть.
 *
 * <p>Право проверяется на каждый запрос, а не один раз при установке соединения (`D-065`):
 * за время жизни сокета участие успевают забанить, а игру завершить.
 */
@Service
public class GameQueryService {

    /** Верхняя граница страницы журнала: без неё клиент выкачивает всю игру одним запросом (`D-082`). */
    public static final int MAX_PAGE = 200;

    private final GameRepository games;
    private final MembershipRepository memberships;
    private final GameEventRepository events;
    private final com.chattergame.restriction.RestrictionRepository restrictions;

    public GameQueryService(
            GameRepository games,
            MembershipRepository memberships,
            GameEventRepository events,
            com.chattergame.restriction.RestrictionRepository restrictions) {
        this.games = games;
        this.memberships = memberships;
        this.events = events;
        this.restrictions = restrictions;
    }

    /**
     * Запреты игры. Видит мастер целиком, участник — только свои: запрет адресован
     * конкретному персонажу или участию (`D-064`), и список всех раскрыл бы игроку, кого
     * мастер ограничил и почему.
     */
    @Transactional(readOnly = true)
    public List<com.chattergame.restriction.Restriction> restrictions(UUID userId, UUID gameId) {
        requireReadable(userId, gameId);
        Membership membership = memberships.find(gameId, userId).orElseThrow();
        if (membership.role() == MembershipRole.MASTER) {
            return restrictions.findByGame(gameId);
        }
        return restrictions.findForMembership(gameId, membership.id());
    }

    @Transactional(readOnly = true)
    public Game requireReadable(UUID userId, UUID gameId) {
        Game game = games.findById(gameId)
                .orElseThrow(() -> ApiException.notFound("game_not_found", "Игра не найдена"));
        Membership membership = memberships.find(gameId, userId)
                .orElseThrow(() -> ApiException.forbidden("not_a_member", "Нет доступа к этой игре"));
        if (membership.banned()) {
            throw ApiException.forbidden("membership_banned", "Участие в этой игре запрещено мастером");
        }
        return game;
    }

    /** Игры пользователя: и как мастера, и как участника. Публичный список — задача `F01`. */
    @Transactional(readOnly = true)
    public List<Game> myGames(UUID userId) {
        return games.findByMember(userId);
    }

    /**
     * Страница журнала по возрастанию `seq`. Фильтр по типу необязателен, лимит зажат
     * сверху: клиент не должен иметь возможности запросить журнал целиком (`D-082`).
     */
    @Transactional(readOnly = true)
    public List<GameEvent> events(
            UUID userId, UUID gameId, long afterSeq, GameEventType type, int limit) {
        requireReadable(userId, gameId);
        int size = limit <= 0 ? MAX_PAGE : Math.min(limit, MAX_PAGE);
        return events.pageAfter(gameId, Math.max(afterSeq, 0L), type, size);
    }

    @Transactional(readOnly = true)
    public long lastSeq(UUID userId, UUID gameId) {
        requireReadable(userId, gameId);
        return events.lastSeq(gameId);
    }
}
