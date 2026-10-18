package com.chattergame.notification;

import com.chattergame.journal.GameEvent;
import com.chattergame.journal.GameEventRepository;
import com.chattergame.realtime.Fanout;
import com.chattergame.realtime.GameEventRef;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Сухой прогон отправки: считает адресатов и пишет в лог, но ничего не отправляет
 * (`PLAN` O03, `D-070`, `D-148`).
 *
 * <p>Транспорта нет намеренно. Проверить «уведомление ушло бы такому-то по такому каналу»
 * можно и без него, а подключённый транспорт пришлось бы отключать настройкой в тестах и
 * держать в проде выключенным — то есть код, который никогда не работал, пока им не
 * начнут пользоваться. Когда транспорт появится, он встанет на место этого класса: журнал
 * и `inbox_entry` при этом не меняются, что и требуется от `D-070`.
 *
 * <p>В лог попадают игра, номер события, тип уведомления и канал — без идентификаторов
 * людей, имён персонажей и текста сообщений (`D-046`, `AGENTS.md`).
 */
@Component
public class NotificationDryRun {

    private static final Logger log = LoggerFactory.getLogger(NotificationDryRun.class);

    private final Fanout fanout;
    private final GameEventRepository events;
    private final NotificationCandidates candidates;
    private final NotificationPreferences preferences;

    private Fanout.Subscription subscription;

    public NotificationDryRun(
            Fanout fanout,
            GameEventRepository events,
            NotificationCandidates candidates,
            NotificationPreferences preferences) {
        this.fanout = fanout;
        this.events = events;
        this.candidates = candidates;
        this.preferences = preferences;
    }

    @PostConstruct
    void start() {
        subscription = fanout.subscribe(this::onEvent);
    }

    @PreDestroy
    void stop() {
        if (subscription != null) {
            subscription.close();
        }
    }

    private void onEvent(GameEventRef ref) {
        try {
            events.find(ref.gameId(), ref.seq()).ifPresent(this::dispatch);
        } catch (RuntimeException e) {
            log.warn("Уведомления по событию {} не посчитаны: {}", ref, e.toString());
        }
    }

    /**
     * Кандидаты события с назначенным каналом. Возвращает то, что было бы отправлено, —
     * тесту нужен результат, а не только строка в логе.
     */
    public List<Planned> dispatch(GameEvent event) {
        List<Planned> planned = new ArrayList<>();
        for (NotificationCandidate candidate : candidates.forEvent(event)) {
            NotificationChannel channel = preferences.channelFor(candidate.userId(), candidate.event());
            if (channel == NotificationChannel.NONE) {
                continue;
            }
            planned.add(new Planned(candidate, channel));
        }
        if (!planned.isEmpty()) {
            log.info("notification dry-run game_id={} event_seq={} type={} channel={} recipients={}",
                    event.gameId(),
                    event.seq(),
                    planned.get(0).candidate().event(),
                    planned.get(0).channel(),
                    planned.size());
        }
        return planned;
    }

    /** Что ушло бы: кандидат и канал, выбранный получателем. */
    public record Planned(NotificationCandidate candidate, NotificationChannel channel) {
    }
}
