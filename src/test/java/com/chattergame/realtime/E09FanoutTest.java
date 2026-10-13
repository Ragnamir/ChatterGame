package com.chattergame.realtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Контракт фан-аута (`PLAN` E09, задача 5, `D-084`).
 *
 * <p>Один и тот же набор проверок идёт по обеим реализациям: `InJvmFanout` для одного
 * инстанса и `PostgresNotifyFanout` для нескольких. Разные наборы тестов здесь были бы
 * ошибкой — вторая реализация разошлась бы с первой на первом же изменении, а признака
 * не было бы: оба класса «просто передают ссылку».
 *
 * <p>Уведомление несёт только идентификатор события. Тело читается из таблицы, потому что
 * `NOTIFY` ограничен 8 KB и не даёт гарантий доставки: он триггер, а не транспорт.
 *
 * <p>Ожидание везде идёт по предикату, а не по голове очереди: канал `LISTEN/NOTIFY` общий
 * на процесс, и уведомление соседнего теста может опередить своё. Проверка «моя ссылка
 * дошла» от этого не слабеет, а проверка «чужая не дошла» сравнивает идентификаторы, а не
 * пустоту очереди.
 */
class E09FanoutTest extends E09TestSupport {

    private static final long WAIT_SECONDS = 10;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<PostgresNotifyFanout> postgres = new ArrayList<>();

    /** Реализации под тестом: у каждой своя причина существовать, у обеих — один договор. */
    static Stream<Arguments> fanouts() {
        return Stream.of(Arguments.of("in-jvm"), Arguments.of("postgres"));
    }

    @AfterEach
    void stopListeners() {
        postgres.forEach(PostgresNotifyFanout::stop);
        postgres.clear();
    }

    /** Первое событие, удовлетворяющее предикату: чужие уведомления пропускаются. */
    private static GameEventRef await(
            BlockingQueue<GameEventRef> received, Predicate<GameEventRef> predicate) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            GameEventRef event = received.poll(200, TimeUnit.MILLISECONDS);
            if (event != null && predicate.test(event)) {
                return event;
            }
        }
        return null;
    }

    /** Всё, что пришло за окно: используется там, где проверяется отсутствие события. */
    private static List<GameEventRef> collect(BlockingQueue<GameEventRef> received, long millis)
            throws InterruptedException {
        List<GameEventRef> events = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() < deadline) {
            GameEventRef event = received.poll(50, TimeUnit.MILLISECONDS);
            if (event != null) {
                events.add(event);
            }
        }
        return events;
    }

    private Fanout fanoutOf(String name) {
        if ("in-jvm".equals(name)) {
            return new InJvmFanout();
        }
        PostgresNotifyFanout created = new PostgresNotifyFanout(dataSource, jdbc);
        postgres.add(created);
        return created;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fanouts")
    void deliversPublishedReferenceToSubscriber(String name) throws Exception {
        Fanout fanout = fanoutOf(name);
        BlockingQueue<GameEventRef> received = new LinkedBlockingQueue<>();
        GameEventRef ref = new GameEventRef(UUID.randomUUID(), 42L);

        try (Fanout.Subscription subscription = fanout.subscribe(received::add)) {
            fanout.publish(ref);
            assertThat(await(received, ref::equals)).isEqualTo(ref);
        }
    }

    /**
     * Публикация до подписки не приходит: канал не хранит прошлого, пробел восполняет resume
     * (`D-083`). Проверка идёт по идентификаторам, а не по пустоте очереди: канал
     * `LISTEN/NOTIFY` общий на процесс, и уведомление соседнего теста опережает своё.
     * Прошлое канал не проигрывает — иначе клиент получал бы вечно растущую очередь истории
     * при каждом переподключении.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("fanouts")
    void doesNotReplayHistoryAfterSubscription(String name) throws Exception {
        Fanout fanout = fanoutOf(name);
        BlockingQueue<GameEventRef> received = new LinkedBlockingQueue<>();
        GameEventRef past = new GameEventRef(UUID.randomUUID(), 1L);
        GameEventRef current = new GameEventRef(UUID.randomUUID(), 2L);

        fanout.publish(past);

        try (Fanout.Subscription subscription = fanout.subscribe(received::add)) {
            fanout.publish(current);

            assertThat(await(received, current::equals))
                    .as("опубликованное после подписки доходит")
                    .isEqualTo(current);
            assertThat(collect(received, 300))
                    .as("прошлое канал не проигрывает: пробел восполняет resume, а не фан-аут")
                    .doesNotContain(past);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fanouts")
    void closedSubscriptionStopsDelivery(String name) throws Exception {
        Fanout fanout = fanoutOf(name);
        BlockingQueue<GameEventRef> received = new LinkedBlockingQueue<>();
        Fanout.Subscription subscription = fanout.subscribe(received::add);
        subscription.close();

        GameEventRef afterClose = new GameEventRef(UUID.randomUUID(), 7L);
        fanout.publish(afterClose);

        assertThat(collect(received, 500)).doesNotContain(afterClose);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fanouts")
    void everyListenerGetsTheReference(String name) throws Exception {
        Fanout fanout = fanoutOf(name);
        BlockingQueue<GameEventRef> first = new LinkedBlockingQueue<>();
        BlockingQueue<GameEventRef> second = new LinkedBlockingQueue<>();
        GameEventRef ref = new GameEventRef(UUID.randomUUID(), 3L);

        try (Fanout.Subscription one = fanout.subscribe(first::add);
                Fanout.Subscription two = fanout.subscribe(second::add)) {
            fanout.publish(ref);
            assertThat(await(first, ref::equals)).isEqualTo(ref);
            assertThat(await(second, ref::equals)).isEqualTo(ref);
        }
    }

    /** Полезная нагрузка уведомления — только `(game_id, seq)`: тела в ней быть не может. */
    @Test
    void referenceCarriesNothingButTheEventIdentifier() {
        UUID gameId = UUID.randomUUID();
        GameEventRef ref = new GameEventRef(gameId, 99L);

        assertThat(ref.toString()).isEqualTo(gameId + ":99");
        assertThat(GameEventRef.parse(ref.toString())).isEqualTo(ref);
        assertThat(GameEventRef.parse(ref.toString()).seq()).isEqualTo(99L);
    }

    /**
     * Второй инстанс: публикация на одном доходит до подписчика другого. Это и есть причина
     * существования `PostgresNotifyFanout` — без него сокет пользователя на инстансе `A`
     * не увидел бы событие, порождённое на инстансе `B`, и признака ошибки не было бы.
     */
    @Test
    void postgresFanoutCrossesInstances() throws Exception {
        PostgresNotifyFanout listenerInstance = new PostgresNotifyFanout(dataSource, jdbc);
        PostgresNotifyFanout publisherInstance = new PostgresNotifyFanout(dataSource, jdbc);
        postgres.add(listenerInstance);
        postgres.add(publisherInstance);
        BlockingQueue<GameEventRef> received = new LinkedBlockingQueue<>();
        GameEventRef ref = new GameEventRef(UUID.randomUUID(), 5L);

        try (Fanout.Subscription listener = listenerInstance.subscribe(received::add);
                Fanout.Subscription warmup = publisherInstance.subscribe(delivered -> { })) {
            assertThat(warmup).isNotNull();
            publisherInstance.publish(ref);
            assertThat(await(received, ref::equals)).isEqualTo(ref);
        }
    }

    /** Уведомление уходит в отдельный канал: его же слушает `LISTEN` на втором инстансе. */
    @Test
    void postgresFanoutUsesItsOwnNotifyChannel() {
        assertThat(PostgresNotifyFanout.CHANNEL).isEqualTo("chattergame_events");
    }
}
