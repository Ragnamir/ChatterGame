package com.chattergame.turn;

import com.chattergame.character.Character;
import com.chattergame.character.CharacterRepository;
import com.chattergame.game.Game;
import com.chattergame.game.GameRepository;
import com.chattergame.game.Membership;
import com.chattergame.game.MembershipRepository;
import com.chattergame.game.MembershipRole;
import com.chattergame.message.MessageKind;
import com.chattergame.util.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Расход бюджета хода и чтение счётчика (`PLAN` E10, задачи 4–7).
 *
 * <p>Открытие и закрытие хода живут в {@code GameMutationService}: это мутация мира с
 * событием `TURN_OPENED`/`TURN_CLOSED` и проверкой мастера, и она подчиняется общему
 * правилу «мутация — одно горло» (`D-034`). Здесь остаётся то, что мутацией мира не
 * является: списание лимита — часть записи сообщения, ровно как строки `inbox_entry`
 * в `MessageService`. Отдельного события у списания нет: сообщение и его расход — одно
 * событие `MESSAGE_POSTED` (`D-033`), иначе журнал получил бы две записи об одном действии.
 *
 * <p>Ядро — {@link #charge}: проверка «есть ли что тратить» и трата происходят под
 * блокировкой строки бюджета. Разделить их нельзя: два чата одного персонажа дали бы
 * двойной лимит (`D-013`), если бы проверка и запись шли разными запросами.
 *
 * <p>Порядок блокировок единый и обязательный: строка игры, затем строка хода. Первой
 * берётся <em>та же</em> блокировка игры, которую возьмёт внешний ключ `game_event` при
 * вставке события, поэтому к моменту записи она уже своя. Обратный порядок дал бы взаимную
 * блокировку с закрытием хода: сообщение держало бы ход и ждало игру, а закрытие —
 * наоборот (проверено: `postingWhileTheTurnIsBeingClosedEndsInOneOfTwoDefiniteStates`).
 */
@Service
public class TurnService {

    private final GameRepository games;
    private final MembershipRepository memberships;
    private final CharacterRepository characters;
    private final TurnRepository turns;

    public TurnService(
            GameRepository games,
            MembershipRepository memberships,
            CharacterRepository characters,
            TurnRepository turns) {
        this.games = games;
        this.memberships = memberships;
        this.characters = characters;
        this.turns = turns;
    }

    /**
     * Списание лимита на сообщение персонажа. Вызывается из пути записи сообщения внутри
     * его транзакции (`MANDATORY`): лимит и сообщение обязаны фиксироваться вместе, иначе
     * отклонённая запись всё равно съела бы ход.
     *
     * <p>Правила (`REQUIREMENTS §14`): `SPEECH` тратит речь, `ACTION` — действие, лимит
     * суммарный по чатам. Личный чат с мастером и нерасходующие виды сообщений сюда не
     * попадают вовсе — их отсекает вызывающий (`D-013`, `D-005`).
     *
     * <p>Выключенный режим не блокирует запись: в игре без счётчика лимита просто нет.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void charge(UUID gameId, UUID characterId, MessageKind kind) {
        Game game = games.findById(gameId)
                .orElseThrow(() -> ApiException.notFound("game_not_found", "Игра не найдена"));
        if (!game.turnBased()) {
            return;
        }

        games.lockByIdForShare(gameId);
        Turn turn = turns.lockOpenForShare(gameId)
                .orElseThrow(() -> ApiException.conflict(
                        "turn_not_open", "Ход не открыт: мастер ещё не начал ход"));

        boolean action = kind == MessageKind.ACTION;
        TurnBudget budget = turns.lockBudget(turn.id(), characterId);
        if (action && budget.actionUsed()) {
            throw ApiException.conflict(
                    "turn_action_used", "Действие в этом ходу уже использовано");
        }
        if (!action && budget.speechUsed()) {
            throw ApiException.conflict(
                    "turn_speech_used", "Речь в этом ходу уже использована");
        }

        boolean actionUsed = action || budget.actionUsed();
        boolean speechUsed = !action || budget.speechUsed();
        turns.updateUsage(new TurnBudget(budget.id(), characterId, actionUsed, speechUsed));
    }

    /** Счётчик хода глазами участника: мастер видит расход всех, игрок — только своих. */
    @Transactional(readOnly = true)
    public TurnState state(UUID userId, UUID gameId) {
        Game game = games.findById(gameId)
                .orElseThrow(() -> ApiException.notFound("game_not_found", "Игра не найдена"));
        Membership membership = memberships.find(gameId, userId)
                .orElseThrow(() -> ApiException.forbidden("not_a_member", "Нет доступа к этой игре"));

        Turn turn = turns.findLatest(gameId).orElse(null);
        return TurnState.of(game.turnBased(), turn, visibleBudgets(gameId, membership, turn));
    }

    /**
     * Расход хода, который смотрящему позволено видеть. Наблюдатель не видит ничьего
     * расхода: он читает игру, а не играет в неё (`D-063`). Игрок видит только своих
     * персонажей — чужой лимит это сведения о том, кто ещё не ходил, и мастеру их видно
     * и без участника.
     */
    private List<TurnBudget> visibleBudgets(UUID gameId, Membership membership, Turn turn) {
        if (turn == null) {
            return List.of();
        }
        List<TurnBudget> spent = turns.findBudgets(turn.id());
        if (membership.role() == MembershipRole.MASTER) {
            return spent;
        }
        Set<UUID> own = ownCharacters(gameId, membership);
        if (own.isEmpty()) {
            return List.of();
        }
        return spent.stream().filter(budget -> own.contains(budget.characterId())).toList();
    }

    /** Персонажи, расход которых принадлежит участию: у наблюдателя их нет по определению. */
    private Set<UUID> ownCharacters(UUID gameId, Membership membership) {
        if (membership.role() == MembershipRole.OBSERVER) {
            return Set.of();
        }
        return characters.findByGame(gameId).stream()
                .filter(character -> membership.id().equals(character.occupantMembershipId()))
                .map(Character::id)
                .collect(Collectors.toSet());
    }
}
