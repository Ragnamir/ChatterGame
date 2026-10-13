package com.chattergame.realtime;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/**
 * Единственная точка публикации ссылок на события (`D-084`). Событие уходит в фан-аут
 * после фиксации транзакции: внутри неё слушатель прочитал бы журнал и не нашёл строки —
 * эффект и событие фиксируются вместе (`D-034`), и рассылка не должна обгонять фиксацию.
 */
@Component
public class RealtimeEvents {

    private final Fanout fanout;

    public RealtimeEvents(Fanout fanout) {
        this.fanout = fanout;
    }

    public void publishAfterCommit(UUID gameId, long seq) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    fanout.publish(new GameEventRef(gameId, seq));
                }
            });
            return;
        }
        fanout.publish(new GameEventRef(gameId, seq));
    }
}
