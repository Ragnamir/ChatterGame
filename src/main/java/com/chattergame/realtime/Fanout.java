package com.chattergame.realtime;

import java.util.function.Consumer;

/**
 * Рассылка ссылок на события за интерфейсом (`D-084`). Две реализации: `InJvmFanout` для
 * одного инстанса и `PostgresNotifyFanout` для нескольких. Без абстракции публикация на
 * инстансе B молча не попадала бы в сокет пользователя на инстансе A, и признаков ошибки
 * не было бы вовсе.
 */
public interface Fanout {

    /** Публикация ссылки. Вызывается после фиксации транзакции: иначе слушатель прочтёт
     * событие, которого ещё нет в таблице. */
    void publish(GameEventRef ref);

    /** Подписка слушателя. Возвращаемый объект снимает подписку при закрытии. */
    Subscription subscribe(Consumer<GameEventRef> listener);

    /** Снятие подписки. Отдельный тип, а не `Consumer`, потому что закрытие — часть договора. */
    interface Subscription extends AutoCloseable {

        @Override
        void close();
    }
}
