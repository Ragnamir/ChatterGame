package com.chattergame.turn;

import com.chattergame.message.MessageKind;
import com.chattergame.util.ApiException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Бюджет хода (`PLAN` E10, задачи 4–7, `REQUIREMENTS §11`).
 *
 * <p>Проверяется три вещи, которых в тесте через одно сообщение не видно: лимит суммарный
 * по всем чатам одного персонажа, кроме личного (`D-013`); сообщение мастера от лица
 * персонажа тратит бюджет этого персонажа (`D-005`); `JOKE`, `MASTER` и `SYSTEM` не тратят.
 *
 * <p>Гонка за лимитом идёт через настоящий пул соединений, а не через `MockMvc`: проверка
 * «не потрачено» и запись «потрачено» обязаны быть одним неделимым шагом, и без блокировки
 * строки бюджета два чата одного персонажа дали бы двойной лимит. Мок бы это скрыл.
 */
class E10BudgetTest extends E10TestSupport {

    private static final int ATTEMPTS = 8;

    @Test
    void speechAndActionAreSeparateLimitsOfOneTurn() throws Exception {
        Fixture fixture = turnGame(true);
        Turn turn = fixture.openTurn();

        writes.post(fixture.playerUserId, fixture.gameId, fixture.locationId, fixture.character.id(),
                MessageKind.SPEECH, "речь", false);

        assertThat(budgetsOf(turn))
                .singleElement()
                .satisfies(budget -> {
                    assertThat(budget.speechUsed()).isTrue();
                    assertThat(budget.actionUsed()).as("речь не тратит действие").isFalse();
                });

        writes.post(fixture.playerUserId, fixture.gameId, fixture.locationId, fixture.character.id(),
                MessageKind.ACTION, "действие", false);

        assertThat(budgetsOf(turn))
                .singleElement()
                .satisfies(budget -> {
                    assertThat(budget.speechUsed()).isTrue();
                    assertThat(budget.actionUsed()).isTrue();
                });
    }

    @Test
    void repeatedSpeechInTheSameTurnIsRefused() throws Exception {
        Fixture fixture = turnGame(true);
        fixture.openTurn();
        writes.post(fixture.playerUserId, fixture.gameId, fixture.locationId, fixture.character.id(),
                MessageKind.SPEECH, "первая", false);

        assertThatThrownBy(() -> writes.post(
                fixture.playerUserId, fixture.gameId, fixture.locationId, fixture.character.id(),
                MessageKind.SPEECH, "вторая", false))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo("turn_speech_used");

        assertThat(countRows(
                        "SELECT count(*) FROM message WHERE game_id = ? AND body = 'вторая'",
                        fixture.gameId))
                .as("отказ по лимиту не оставляет сообщения")
                .isZero();
        assertThat(countEvents(fixture.gameId, "MESSAGE_POSTED"))
                .as("и события тоже: журнал помнит мутации, а не отказы")
                .isEqualTo(1);
    }

    @Test
    void postingWithoutAnOpenTurnIsRefused() throws Exception {
        Fixture fixture = turnGame(true);

        assertThatThrownBy(() -> writes.post(
                fixture.playerUserId, fixture.gameId, fixture.locationId, fixture.character.id(),
                MessageKind.SPEECH, "в пустоту", false))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo("turn_not_open");
        assertThat(budgetsOfGame(fixture.gameId))
                .as("в закрытом ходу бюджет не заводится")
                .isZero();
    }

    /**
     * `D-013`: лимит суммарный по чатам, а не по одному чату на выбор. Проверка идёт по
     * ходу персонажа из локации в локацию и в радиочат: одна речь и одно действие на всех.
     */
    @Test
    void theLimitIsSharedAcrossChatsOfOneCharacter() throws Exception {
        Fixture fixture = turnGame(true);
        Turn turn = fixture.openTurn();
        UUID second = fixture.secondLocation("Второе место");
        UUID radio = fixture.channelOfKind("RADIO", "Радио", fixture.character.id());

        writes.post(fixture.playerUserId, fixture.gameId, fixture.locationId, fixture.character.id(),
                MessageKind.SPEECH, "здесь", false);
        writes.post(fixture.playerUserId, fixture.gameId, fixture.locationId, fixture.character.id(),
                MessageKind.ACTION, "и действие", false);

        fixture.place(fixture.character.id(), second);
        assertThatThrownBy(() -> writes.post(
                fixture.playerUserId, fixture.gameId, second, fixture.character.id(),
                MessageKind.SPEECH, "во второй локации", false))
                .as("речь уже израсходована в первой локации")
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo("turn_speech_used");
        assertThatThrownBy(() -> writes.post(
                fixture.playerUserId, fixture.gameId, second, fixture.character.id(),
                MessageKind.ACTION, "действие во второй локации", false))
                .as("действие — отдельный лимит, но и он уже израсходован")
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo("turn_action_used");

        assertThatThrownBy(() -> writes.post(
                fixture.playerUserId, fixture.gameId, radio, fixture.character.id(),
                MessageKind.SPEECH, "в радио", false))
                .as("локация и радиочат делят один лимит (`D-013`)")
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo("turn_speech_used");

        assertThat(budgetsOf(turn))
                .as("три чата — одна строка бюджета")
                .singleElement()
                .satisfies(budget -> {
                    assertThat(budget.speechUsed()).isTrue();
                    assertThat(budget.actionUsed()).isTrue();
                });
    }

    /** `D-013` и `REQUIREMENTS §7.3`: личный чат с мастером бюджета не расходует. */
    @Test
    void theMasterDmLimitIsNotConsumed() throws Exception {
        Fixture fixture = turnGame(true);
        Turn turn = fixture.openTurn();
        UUID masterDm = fixture.masterDm();

        for (int i = 0; i < 3; i++) {
            writes.post(fixture.playerUserId, fixture.gameId, masterDm, fixture.character.id(),
                    MessageKind.SPEECH, "личное " + i, false);
        }

        assertThat(budgetsOf(turn))
                .as("личный чат не тратит и не заводит строк бюджета")
                .isEmpty();
        assertThat(payloadOf(fixture.gameId, lastSeq(fixture.gameId)))
                .as("событие личного чата говорит, что бюджет не израсходован")
                .containsEntry("budgetConsumed", false);

        // Вторая половина проверки: после трёх реплик в личном чате лимит в локации цел.
        fixture.place(fixture.character.id(), fixture.locationId);
        writes.post(fixture.playerUserId, fixture.gameId, fixture.locationId, fixture.character.id(),
                MessageKind.SPEECH, "а в локации ещё можно", false);

        assertThat(budgetsOf(turn))
                .as("расход появился только от речи в локации")
                .singleElement()
                .satisfies(budget -> assertThat(budget.speechUsed()).isTrue());
    }

    /** `REQUIREMENTS §11`: `JOKE` не тратит ни одного из двух лимитов. */
    @Test
    void jokesDoNotConsumeTheLimit() throws Exception {
        Fixture fixture = turnGame(true);
        Turn turn = fixture.openTurn();

        writes.post(fixture.playerUserId, fixture.gameId, fixture.locationId, fixture.character.id(),
                MessageKind.JOKE, "шутка раз", false);
        writes.post(fixture.playerUserId, fixture.gameId, fixture.locationId, fixture.character.id(),
                MessageKind.JOKE, "шутка два", false);

        assertThat(budgetsOf(turn)).isEmpty();
        assertThat(payloadOf(fixture.gameId, lastSeq(fixture.gameId)))
                .as("у шутки флага расхода нет вовсе: она не из пары речь/действие")
                .doesNotContainKey("budgetConsumed");
    }

    @Test
    void worldEventsDoNotConsumeTheLimit() throws Exception {
        Fixture fixture = turnGame(true);
        Turn turn = fixture.openTurn();

        writes.post(fixture.masterUserId, fixture.gameId, fixture.locationId, null,
                MessageKind.MASTER, "гром с неба", false);
        writes.post(fixture.masterUserId, fixture.gameId, fixture.locationId, null,
                MessageKind.MASTER, "и ещё раз", false);

        assertThat(budgetsOf(turn)).isEmpty();
    }

    /**
     * `D-005`: сообщение мастера от лица персонажа — речь персонажа, поэтому лимит
     * тратит он, а не мастер. Иначе мастер мог бы говорить за игрока безгранично, и
     * «одна реплика за ход» превратилась бы в фикцию.
     */
    @Test
    void aMasterMessageOnBehalfOfACharacterConsumesThatCharactersBudget() throws Exception {
        Fixture fixture = turnGame(true);
        Turn turn = fixture.openTurn();

        writes.post(fixture.masterUserId, fixture.gameId, fixture.locationId, fixture.character.id(),
                MessageKind.SPEECH, "сказано мастером за персонажа", true);

        assertThat(budgetsOf(turn))
                .singleElement()
                .satisfies(budget -> assertThat(budget.speechUsed()).isTrue());
        assertThat(payloadOf(fixture.gameId, lastSeq(fixture.gameId)))
                .as("журнал отличает это сообщение от речи самого игрока (`D-005`)")
                .containsEntry("onBehalfOfMaster", true)
                .containsEntry("budgetConsumed", true);
        assertThatThrownBy(() -> writes.post(
                fixture.playerUserId, fixture.gameId, fixture.locationId, fixture.character.id(),
                MessageKind.SPEECH, "спасибо", false))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo("turn_speech_used");
    }

    /** Мастер без персонажа (`MASTER`) в пошаговом режиме не упирается ни в собственный лимит. */
    @Test
    void theMasterHimselfHasNoLimit() throws Exception {
        Fixture fixture = turnGame(true);
        Turn turn = fixture.openTurn();

        for (int i = 0; i < 5; i++) {
            writes.post(fixture.masterUserId, fixture.gameId, fixture.locationId, null,
                    MessageKind.MASTER, "событие " + i, false);
        }

        assertThat(budgetsOf(turn)).isEmpty();
    }

    @Test
    void aFreshTurnResetsTheLimits() throws Exception {
        Fixture fixture = turnGame(true);
        Turn first = fixture.openTurn();
        writes.post(fixture.playerUserId, fixture.gameId, fixture.locationId, fixture.character.id(),
                MessageKind.SPEECH, "в первом ходу", false);
        fixture.closeTurn();

        Turn second = fixture.openTurn();
        writes.post(fixture.playerUserId, fixture.gameId, fixture.locationId, fixture.character.id(),
                MessageKind.SPEECH, "во втором ходу", false);

        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(budgetsOf(second))
                .as("второй ход считает заново, а первый остаётся как был")
                .singleElement()
                .satisfies(budget -> assertThat(budget.speechUsed()).isTrue());
        assertThat(budgetsOf(first)).hasSize(1);
    }

    /**
     * Смена хода на середине: пока мастер не закрыл ход, реплика тратит старый лимит, и
     * только новый ход даёт новый. Проверяется вместе с блокировкой — сообщение и смена
     * хода идут одновременно, и исход обязан быть один из двух, но не оба.
     */
    @Test
    void postingWhileTheTurnIsBeingClosedEndsInOneOfTwoDefiniteStates() throws Exception {
        Fixture fixture = turnGame(true);
        fixture.openTurn();

        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger posted = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            pool.submit(() -> run(ready, go, () -> {
                writes.post(fixture.playerUserId, fixture.gameId, fixture.locationId, fixture.character.id(),
                        MessageKind.SPEECH, "спор", false);
                posted.incrementAndGet();
            }, failures));
            pool.submit(() -> run(ready, go, fixture::closeTurn, failures));

            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        for (Throwable failure : failures) {
            assertThat(failure).isInstanceOf(ApiException.class);
            assertThat(((ApiException) failure).code())
                    .as("исходов только два: ход уже закрыт или речь уже потрачена")
                    .isIn("turn_not_open", "turn_speech_used");
        }
        assertThat(posted.get())
                .as("исходов ровно два: сообщение прошло или его отклонили, но не «ни то ни сё»")
                .isBetween(0, 1);
        assertThat(countRows(
                        "SELECT count(*) FROM message WHERE game_id = ? AND body = 'спор'",
                        fixture.gameId))
                .as("сообщение либо записано, либо отклонено целиком")
                .isEqualTo(posted.get());
        assertThat(turnRepository.findOpen(fixture.gameId)).isEmpty();
    }

    /**
     * Два сообщения одного персонажа одновременно. Без блокировки строки бюджета оба
     * прошли бы проверку «ещё не сказано», и лимит превратился бы в двойной — ровно та
     * дыра, которую закрывает `D-013`. Проверка идёт через настоящий пул соединений:
     * `MockMvc` на одном потоке такую гонку не показал бы вовсе.
     */
    @Test
    void concurrentPostsCannotBothSpendTheLastSpeech() throws Exception {
        Fixture fixture = turnGame(true);
        Turn turn = fixture.openTurn();

        List<String> codes = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger successes = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(ATTEMPTS);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(ATTEMPTS);
        try {
            for (int i = 0; i < ATTEMPTS; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        writes.post(fixture.playerUserId, fixture.gameId, fixture.locationId,
                                fixture.character.id(), MessageKind.SPEECH, "одновременно", false);
                        successes.incrementAndGet();
                    } catch (ApiException e) {
                        codes.add(e.code());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(successes.get()).as("победитель ровно один").isEqualTo(1);
        assertThat(codes)
                .as("проигравшие получили отказ «речь уже использована»")
                .hasSize(ATTEMPTS - 1)
                .allSatisfy(code -> assertThat(code).isEqualTo("turn_speech_used"));
        assertThat(budgetsOf(turn)).singleElement();
        assertThat(countRows(
                        "SELECT count(*) FROM message WHERE game_id = ? AND body = 'одновременно'",
                        fixture.gameId))
                .as("в журнал попало одно действие, остальные отказы мира не меняли")
                .isEqualTo(1);
    }

    static void run(CountDownLatch ready, CountDownLatch go, Runnable body, List<Throwable> failures) {
        ready.countDown();
        try {
            go.await();
            body.run();
        } catch (ApiException e) {
            failures.add(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable e) {
            failures.add(e);
        }
    }

    private long lastSeq(UUID gameId) {
        Long seq = jdbc.queryForObject(
                "SELECT max(seq) FROM game_event WHERE game_id = ?", Long.class, gameId);
        return seq == null ? 0L : seq;
    }
}
