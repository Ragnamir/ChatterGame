package com.chattergame.turn;

import com.chattergame.journal.GameEventType;
import com.chattergame.message.MessageKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Счётчик хода восстанавливается из журнала (`PLAN` E10, критерий приёмки).
 *
 * <p>Проверка идёт с двух сторон. Сначала игра прогоняется сценарием, и после каждого шага
 * снимается материализованное состояние из `turn`. Затем тот же сценарий читается как
 * поток событий и сворачивается с нуля, по одному событию за раз, — свернутое состояние
 * обязано совпасть с материализованным на каждом шаге, а не только в конце. Совпадение
 * лишь на последнем шаге ничего не доказывало бы: счётчик мог разойтись в середине и
 * вернуться к тому же числу.
 *
 * <p>Материализованное состояние — кэш журнала, а не вторая правда (`D-033`): тест падает,
 * если они разойдутся, значит прод-решение «счётчик читается из своей таблицы, а журнал
 * остаётся источником» не расходится с записью.
 */
class E10ReplayTest extends E10TestSupport {

    @Test
    void replayedCounterMatchesTheMaterialisedOneAtEveryStep() throws Exception {
        Fixture fixture = turnGame(true);
        List<Snapshot> materialised = new ArrayList<>();

        fixture.openTurn();
        materialised.add(current(fixture.gameId));
        fixture.closeTurn();
        materialised.add(current(fixture.gameId));
        fixture.openTurn();
        writes.post(fixture.playerUserId, fixture.gameId, fixture.locationId, fixture.character.id(),
                MessageKind.SPEECH, "во втором ходу", false);
        materialised.add(current(fixture.gameId));
        fixture.closeTurn();
        materialised.add(current(fixture.gameId));
        fixture.openTurn();
        materialised.add(current(fixture.gameId));

        List<Snapshot> replayed = replay(fixture.gameId);

        assertThat(replayed)
                .as("состояние после N событий равно состоянию, полученному применением "
                        + "этих событий по одному")
                .containsExactlyElementsOf(materialised);
        assertThat(replayed.getLast()).as("после сверки журнал и таблица дают одно")
                .isEqualTo(current(fixture.gameId));
        assertThat(replayed.getLast().n()).isEqualTo(3L);
        assertThat(replayed.getLast().open()).isTrue();
    }

    /** Номер хода в журнале равен номеру в таблице: два независимых счётчика совпадают. */
    @Test
    void theEventNumberAndTheTableNumberAreTheSame() throws Exception {
        Fixture fixture = turnGame(true);

        fixture.openTurn();
        fixture.closeTurn();
        fixture.openTurn();

        assertThat(eventNumbers(fixture.gameId))
                .as("в payload каждого TURN_OPENED лежит номер хода")
                .containsExactly(1L, 2L);
        assertThat(replay(fixture.gameId))
                .as("свертка видит открытие, закрытие и новое открытие")
                .hasSize(3)
                .last()
                .satisfies(snapshot -> assertThat(snapshot.n()).isEqualTo(2L));
        assertThat(current(fixture.gameId))
                .isEqualTo(new Snapshot(2L, true));
    }

    /** Свернутое состояние доходит и до читающего пути: он отдаёт то же, что журнал. */
    @Test
    void theReadingPathAgreesWithTheJournal() throws Exception {
        Fixture fixture = turnGame(true);
        fixture.openTurn();
        fixture.closeTurn();

        TurnState state = turns.state(fixture.masterUserId, fixture.gameId);

        assertThat(state.n()).isEqualTo(replay(fixture.gameId).getLast().n());
        assertThat(state.open()).isEqualTo(replay(fixture.gameId).getLast().open());
        assertThat(state.turnBased()).isTrue();
    }

    /** Ячейка свернутого состояния: номер последнего хода и признак «открыт». */
    private record Snapshot(long n, boolean open) {
    }

    private Snapshot current(UUID gameId) {
        return turnRepository.findLatest(gameId)
                .map(turn -> new Snapshot(turn.n(), turn.open()))
                .orElse(new Snapshot(0L, false));
    }

    /**
     * Свёртка журнала с нуля. Шаг за шагом, ровно как читал бы восстановитель: открытие
     * задаёт номер, закрытие меняет только признак. События других типов пропускаются —
     * на счётчик хода они не влияют, и подмешивать их значило бы проверять не то правило.
     */
    private List<Snapshot> replay(UUID gameId) {
        List<Snapshot> states = new ArrayList<>();
        long n = 0L;
        boolean open = false;
        for (Row row : turnEvents(gameId)) {
            if (GameEventType.TURN_OPENED.name().equals(row.type())) {
                n = row.n();
                open = true;
            } else if (GameEventType.TURN_CLOSED.name().equals(row.type())) {
                open = false;
            } else {
                continue;
            }
            states.add(new Snapshot(n, open));
        }
        return states;
    }

    private List<Long> eventNumbers(UUID gameId) {
        return turnEvents(gameId).stream()
                .filter(row -> GameEventType.TURN_OPENED.name().equals(row.type()))
                .map(Row::n)
                .toList();
    }

    private record Row(String type, long n) {
    }

    /** События хода по возрастанию номера журнала: порядок — часть свертки. */
    private List<Row> turnEvents(UUID gameId) {
        return jdbc.query(
                "SELECT type, coalesce((payload ->> 'n')::bigint, 0) AS n FROM game_event "
                        + "WHERE game_id = ? AND type IN ('TURN_OPENED', 'TURN_CLOSED') ORDER BY seq",
                (rs, rowNum) -> new Row(rs.getString("type"), rs.getLong("n")),
                gameId);
    }
}
