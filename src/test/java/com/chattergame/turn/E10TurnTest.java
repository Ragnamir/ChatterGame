package com.chattergame.turn;

import com.chattergame.game.GameMutationService;
import com.chattergame.game.GameTransition;
import com.chattergame.message.Message;
import com.chattergame.message.MessageKind;
import com.chattergame.util.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ход как механика времени (`PLAN` E10, задачи 1–3, 7).
 *
 * <p>Проверяется то, чего не видно в бюджете: номер хода не повторяется и не переиспользуется,
 * открытый ход один на игру, а время не участвует ни в одном решении (`D-060`). Флаг режима —
 * не фаза (`D-012`), поэтому отдельно проверяется, что его переключение не переписывает
 * отправленное раньше.
 */
class E10TurnTest extends E10TestSupport {

    @Autowired
    private GameMutationService mutations;

    @Test
    void openingAndClosingWritesBothEventsAndOneTurnRow() throws Exception {
        Fixture fixture = turnGame(true);

        Turn opened = fixture.openTurn();

        assertThat(opened.n()).as("первый ход игры — номер один").isEqualTo(1L);
        assertThat(opened.open()).isTrue();
        assertThat(opened.closedBy()).isNull();
        assertThat(eventTypes(fixture.gameId)).contains("TURN_OPENED");
        assertThat(payloadOf(fixture.gameId, lastSeq(fixture.gameId)))
                .as("номер хода попадает в журнал: таблица и журнал читаются вместе")
                .containsEntry("n", 1);

        Turn closed = fixture.closeTurn();

        assertThat(closed.open()).isFalse();
        assertThat(closed.closedBy()).as("автор закрытия — мастер").isEqualTo(fixture.masterUserId);
        assertThat(closed.closedAt()).isNotNull();
        assertThat(countEvents(fixture.gameId, "TURN_OPENED")).isEqualTo(1);
        assertThat(countEvents(fixture.gameId, "TURN_CLOSED")).isEqualTo(1);
        assertThat(countRows("SELECT count(*) FROM turn WHERE game_id = ?", fixture.gameId))
                .as("закрытие не заводит второй строки: ход тот же (`D-010`)")
                .isEqualTo(1);
    }

    @Test
    void turnNumbersGrowAndNeverRepeat() throws Exception {
        Fixture fixture = turnGame(true);

        for (long expected = 1L; expected <= 3L; expected++) {
            assertThat(fixture.openTurn().n()).isEqualTo(expected);
            fixture.closeTurn();
        }

        assertThat(countEvents(fixture.gameId, "TURN_OPENED")).isEqualTo(3);
        assertThat(countRows(
                        "SELECT count(*) FROM turn WHERE game_id = ? AND status = 'OPEN'", fixture.gameId))
                .as("открытый ход ровно один")
                .isZero();
    }

    @Test
    void repeatedOpenIsRefusedInsteadOfSilentlyReplacingTheTurn() throws Exception {
        Fixture fixture = turnGame(true);
        fixture.openTurn();

        assertThatThrownBy(fixture::openTurn)
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo("turn_already_open");

        assertThat(countEvents(fixture.gameId, "TURN_OPENED"))
                .as("отказ не оставляет события: журнал помнит мутации, а не попытки (`D-033`)")
                .isEqualTo(1);
    }

    @Test
    void closingWithoutAnOpenTurnIsRefused() throws Exception {
        Fixture fixture = turnGame(true);

        assertThatThrownBy(fixture::closeTurn)
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo("turn_not_open");
    }

    @Test
    void openingNeedsTheFlagTheTurnBelongsTo() throws Exception {
        Fixture fixture = turnGame(false);

        assertThatThrownBy(fixture::openTurn)
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo("turn_based_off");
        assertNoTurnRows(fixture.gameId);
    }

    @Test
    void onlyTheMasterMovesTheTurn() throws Exception {
        Fixture fixture = turnGame(true);
        fixture.openTurn();

        assertThatThrownBy(() -> mutations.openTurn(fixture.playerUserId, fixture.gameId))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo("not_master");
        assertThatThrownBy(() -> mutations.closeTurn(fixture.playerUserId, fixture.gameId))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo("not_master");
    }

    /**
     * Отсутствие таймаута (`D-060`): ход, открытый трое суток назад, остаётся открытым,
     * потому что ни одно решение не считается по времени. Обратное поведение — «истёк»
     * — потребовало бы нового состояния и таймера, которых в проекте нет.
     */
    @Test
    void aTurnLeftOpenForDaysStaysOpenAndKeepsItsLimits() throws Exception {
        Fixture fixture = turnGame(true);
        Turn turn = fixture.openTurn();

        jdbc.update("UPDATE turn SET opened_at = now() - interval '3 days' WHERE id = ?", turn.id());

        assertThat(turnRepository.findOpen(fixture.gameId))
                .as("ход не закрывается сам собой")
                .isPresent();
        assertThat(countRows(
                        "SELECT count(*) FROM turn WHERE game_id = ? AND status = 'OPEN'", fixture.gameId))
                .isEqualTo(1);
        assertThat(countEvents(fixture.gameId, "TURN_CLOSED"))
                .as("время не порождает событий")
                .isZero();

        Message speech = writes.post(
                fixture.playerUserId, fixture.gameId, fixture.locationId, fixture.character.id(),
                MessageKind.SPEECH, "ход всё ещё идёт", false);
        assertThat(speech).isNotNull();
        assertThat(countRows("SELECT count(*) FROM turn_budget WHERE turn_id = ?", turn.id()))
                .as("лимит списан, а не обнулён временем")
                .isEqualTo(1);
    }

    /**
     * Флаг — не фаза (`D-012`): включение и выключение посреди партии не переписывает
     * ни строк `message`, ни журнал. Проверка идёт по событию, а не по состоянию: именно
     * запись обязана остаться прежней.
     */
    @Test
    void togglingTheFlagDoesNotRewriteAlreadyRecordedMessages() throws Exception {
        Fixture fixture = turnGame(false);

        Message before = writes.post(
                fixture.playerUserId, fixture.gameId, fixture.locationId, fixture.character.id(),
                MessageKind.SPEECH, "сказано до режима", false);
        Map<String, Object> payloadBefore = payloadOf(fixture.gameId, before.seq());

        fixture.setTurnBased(true);
        fixture.setTurnBased(false);

        assertThat(payloadOf(fixture.gameId, before.seq()))
                .as("событие, записанное до включения режима, не менялось")
                .isEqualTo(payloadBefore);
        assertThat(countRows("SELECT count(*) FROM turn WHERE game_id = ?", fixture.gameId))
                .as("переключение флага ход не открывает")
                .isZero();
        assertThat(countEvents(fixture.gameId, "TURN_OPENED")).isZero();

        // Выключённый режим не блокирует и не тратит: две речи подряд проходят.
        writes.post(fixture.playerUserId, fixture.gameId, fixture.locationId, fixture.character.id(),
                MessageKind.SPEECH, "раз", false);
        writes.post(fixture.playerUserId, fixture.gameId, fixture.locationId, fixture.character.id(),
                MessageKind.SPEECH, "два", false);
        assertThat(countRows(
                        "SELECT count(*) FROM turn_budget b JOIN turn t ON t.id = b.turn_id "
                                + "WHERE t.game_id = ?",
                        fixture.gameId))
                .isZero();
    }

    /** Выключенный флаг снимает блокировку и расход, но ход остаётся историей (`D-012`). */
    @Test
    void turningTheFlagOffSuspendsTheOpenTurnWithoutClosingIt() throws Exception {
        Fixture fixture = turnGame(true);
        Turn turn = fixture.openTurn();
        writes.post(fixture.playerUserId, fixture.gameId, fixture.locationId, fixture.character.id(),
                MessageKind.SPEECH, "первая", false);

        fixture.setTurnBased(false);

        writes.post(fixture.playerUserId, fixture.gameId, fixture.locationId, fixture.character.id(),
                MessageKind.SPEECH, "вторая без режима", false);

        assertThat(turnRepository.findOpen(fixture.gameId))
                .as("выключение флага ход не закрывает: сгорание — только при смене хода")
                .isPresent();
        assertThat(countEvents(fixture.gameId, "TURN_CLOSED")).isZero();
        assertThat(budgetsOf(turnRepository.findOpen(fixture.gameId).orElseThrow()))
                .as("расход остался на момент, когда режим был включён")
                .hasSize(1);
    }

    @Test
    void finishedGameDoesNotMoveTheTurn() throws Exception {
        Fixture fixture = turnGame(true);
        fixture.openTurn();
        mutations.applyTransition(fixture.masterUserId, fixture.gameId, GameTransition.FINISH, null);

        assertThatThrownBy(fixture::closeTurn)
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo("turn_after_finish");
        assertThatThrownBy(() -> fixture.setTurnBased(true))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo("turn_based_after_finish");
    }

    /**
     * Момент открытия берётся из строки, а не из часов вызывающего: после вставки ход
     * читается заново, и приложение не приносит собственного представления о времени —
     * иначе две ноды разошлись бы в оценке «когда открыт ход».
     */
    @Test
    void openingRecordsTheMomentFromTheDatabase() throws Exception {
        Fixture fixture = turnGame(true);
        Instant before = Instant.now().minusSeconds(5);

        Turn turn = fixture.openTurn();
        Turn stored = turnRepository.findLatest(fixture.gameId).orElseThrow();

        assertThat(stored.openedAt())
                .as("строка и её чтение дают один момент")
                .isEqualTo(turn.openedAt());
        assertThat(turn.openedAt())
                .as("момент поставлен при открытии, а не взят из прошлого")
                .isAfter(before)
                .isBefore(Instant.now().plusSeconds(5));
        Timestamp column = jdbc.queryForObject(
                "SELECT opened_at FROM turn WHERE id = ?", Timestamp.class, turn.id());
        assertThat(column.toInstant()).isEqualTo(stored.openedAt());
    }

    private long lastSeq(java.util.UUID gameId) {
        Long seq = jdbc.queryForObject(
                "SELECT max(seq) FROM game_event WHERE game_id = ?", Long.class, gameId);
        return seq == null ? 0L : seq;
    }
}
