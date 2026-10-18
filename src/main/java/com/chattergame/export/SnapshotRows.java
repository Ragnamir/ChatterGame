package com.chattergame.export;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Чтение строк снимка в JSON-совместимый вид (`D-146`).
 *
 * <p>Тип берётся из метаданных результата, а не угадывается по значению: `uuid` обязан
 * стать строкой, `timestamptz` — строкой ISO-8601, `jsonb` — разобранным объектом. Без
 * этого `PGobject` уехал бы в файл как `{"type":"jsonb","value":"..."}`, и снимок перестал
 * бы быть машиночитаемым, хотя формально остался бы JSON.
 */
final class SnapshotRows {

    private final ObjectMapper json;

    SnapshotRows(ObjectMapper json) {
        this.json = json;
    }

    List<Map<String, Object>> read(JdbcTemplate jdbc, String sql, Object... args) {
        return jdbc.query(sql, rs -> {
            ResultSetMetaData meta = rs.getMetaData();
            List<String> labels = new ArrayList<>();
            List<String> types = new ArrayList<>();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                labels.add(meta.getColumnLabel(i));
                types.add(meta.getColumnTypeName(i));
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 0; i < labels.size(); i++) {
                    row.put(labels.get(i), value(rs, i + 1, types.get(i)));
                }
                rows.add(row);
            }
            return rows;
        }, args);
    }

    private Object value(ResultSet rs, int index, String type) throws SQLException {
        Object raw = rs.getObject(index);
        if (raw == null) {
            return null;
        }
        return switch (type) {
            case "uuid" -> rs.getObject(index, UUID.class).toString();
            case "timestamptz", "timestamp" -> rs.getTimestamp(index).toInstant().toString();
            case "jsonb", "json" -> parse(rs.getString(index));
            case "bool" -> rs.getBoolean(index);
            case "int2", "int4" -> rs.getInt(index);
            case "int8" -> rs.getLong(index);
            case "numeric" -> rs.getBigDecimal(index);
            default -> rs.getString(index);
        };
    }

    private Object parse(String text) {
        try {
            return json.readValue(text, Object.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Не удалось разобрать jsonb снимка", e);
        }
    }

    /** Обратное преобразование для вставки: тип колонки известен из схемы. */
    @SuppressWarnings("unchecked")
    static Object toColumn(Object value, String dataType) {
        if (value == null) {
            return null;
        }
        return switch (dataType) {
            case "uuid" -> UUID.fromString(value.toString());
            case "timestamp with time zone" ->
                    Timestamp.from(java.time.Instant.parse(value.toString()));
            case "boolean" -> value;
            case "smallint" -> ((Number) value).shortValue();
            case "integer" -> ((Number) value).intValue();
            case "bigint" -> ((Number) value).longValue();
            case "numeric" -> new BigDecimal(value.toString());
            default -> value.toString();
        };
    }
}
