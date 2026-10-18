package com.chattergame.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Читает игру целиком в один снимок (`D-061`, `D-146`).
 *
 * <p>Один проход по списку {@link SnapshotTables} вместо отдельных запросов на каждый раздел
 * отчёта: экспорт и восстановление обязаны видеть одни и те же строки, иначе проверка
 * «восстановилось обратно» сравнивала бы разные выборки.
 */
@Component
public class GameSnapshotReader {

    private final JdbcTemplate jdbc;
    private final SnapshotRows rows;

    public GameSnapshotReader(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.rows = new SnapshotRows(json);
    }

    public Map<String, List<Map<String, Object>>> read(UUID gameId) {
        Map<String, List<Map<String, Object>>> tables = new LinkedHashMap<>();
        for (SnapshotTables.TableSpec spec : SnapshotTables.ALL) {
            tables.put(spec.name(), rows.read(jdbc, spec.select(), gameId));
        }
        return tables;
    }
}
