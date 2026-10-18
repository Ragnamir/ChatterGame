package com.chattergame.ops;

import com.chattergame.journal.GameEventRepository;
import com.chattergame.llm.LlmCallExecutor;
import com.chattergame.media.OrphanAssetCollector;
import com.chattergame.realtime.RealtimeSessions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Эксплуатационные числа и операции (`PLAN` O02, `D-147`).
 *
 * <p>Только чтение и сухой прогон: единственное, что здесь меняет состояние, — счётчики
 * приложения, а не доменные таблицы. Поэтому сервис не идёт через
 * `GameMutationService`: событие журнала для «посмотреть, сколько сокетов» было бы шумом
 * в единственном источнике истины (`D-033`).
 *
 * <p>Числа отвечают на вопросы, которые возникают в проде первыми: сколько людей в эфире
 * по игре, не встала ли очередь к модели, насколько вырос журнал и сколько мусора лежит
 * в медиа. Всё остальное берётся из `pg_stat_*` и логов.
 */
@Service
public class OpsService {

    private final RealtimeSessions sessions;
    private final LlmCallExecutor llm;
    private final OrphanAssetCollector orphans;
    private final GameEventRepository events;
    private final JdbcTemplate jdbc;

    public OpsService(
            RealtimeSessions sessions,
            LlmCallExecutor llm,
            OrphanAssetCollector orphans,
            GameEventRepository events,
            JdbcTemplate jdbc) {
        this.sessions = sessions;
        this.llm = llm;
        this.orphans = orphans;
        this.events = events;
        this.jdbc = jdbc;
    }

    public Map<String, Object> metrics() {
        Map<String, Object> metrics = new LinkedHashMap<>();

        Map<String, Object> sockets = new LinkedHashMap<>();
        sessions.countByGame().forEach((gameId, count) -> sockets.put(gameId.toString(), count));
        metrics.put("sockets", Map.of("total", sessions.size(), "byGame", sockets));

        LlmCallExecutor.Load load = llm.load();
        metrics.put("llm", Map.of(
                "active", load.active(),
                "queued", load.queued(),
                "capacity", load.capacity()));

        metrics.put("journal", journalSize());
        return metrics;
    }

    /**
     * Размер журнала в строках и байтах. Байты считаются по таблицам, которые растут вместе
     * с игрой: `game_event` — сам журнал, `message` и `inbox_entry` — его материализация
     * (`D-030`). Оценка нужна, чтобы увидеть, когда пора смотреть на бэкап и диск.
     */
    private Map<String, Object> journalSize() {
        long total = jdbc.queryForObject("SELECT count(*) FROM game_event", Long.class);
        Map<String, Long> byGame = new LinkedHashMap<>();
        jdbc.query("SELECT game_id, count(*) AS n FROM game_event GROUP BY game_id ORDER BY n DESC",
                rs -> {
                    byGame.put(rs.getString("game_id"), rs.getLong("n"));
                });
        Map<String, Long> bytes = new LinkedHashMap<>();
        for (String table : new String[] {"game_event", "message", "inbox_entry"}) {
            Long size = jdbc.queryForObject(
                    "SELECT pg_total_relation_size(?)", Long.class, table);
            bytes.put(table, size == null ? 0L : size);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("events", total);
        result.put("byGame", byGame);
        result.put("bytes", bytes);
        return result;
    }

    /**
     * Сухой прогон орфан-сборки (`D-077`): оператор видит, что сборщик удалил бы, и решает
     * сам. Отдельная операция нужна потому, что плановый прогон пишет только в лог, а
     * «сколько мусора» — вопрос, который задают до, а не после удаления.
     */
    public OrphanAssetCollector.OrphanReport orphanDryRun() {
        return orphans.collect(true);
    }
}
