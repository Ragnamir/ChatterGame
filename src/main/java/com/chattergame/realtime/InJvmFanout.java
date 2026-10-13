package com.chattergame.realtime;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Фан-аут одного процесса (`D-084`): слушатели живут в памяти, доставка синхронная.
 * `CopyOnWriteArrayList` выбран потому, что подписок единицы, а публикаций много, и
 * блокировать читателя на время рассылки нельзя.
 */
public class InJvmFanout implements Fanout {

    private final CopyOnWriteArrayList<Consumer<GameEventRef>> listeners = new CopyOnWriteArrayList<>();

    @Override
    public void publish(GameEventRef ref) {
        for (Consumer<GameEventRef> listener : listeners) {
            listener.accept(ref);
        }
    }

    @Override
    public Subscription subscribe(Consumer<GameEventRef> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }
}
