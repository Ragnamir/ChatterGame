package com.chattergame.export;

import com.chattergame.game.GameRepository;
import com.chattergame.user.AppUser;
import com.chattergame.user.AppUserRepository;
import com.chattergame.util.ApiException;
import com.chattergame.util.Uuids;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Восстановление игры из снимка (`D-146`).
 *
 * <p>Пишет строки напрямую, а не проигрывает мутации: журнал входит в снимок, и повторное
 * применение операций дало бы другие идентификаторы, другие `seq` и другие отметки времени,
 * то есть не восстановление, а новую игру с похожим содержанием. Журнал — источник истины
 * (`D-033`), и восстанавливать его надо как данные, а не как следствие действий.
 *
 * <p>Пользователи сопоставляются по нормализованному логину: аккаунт, который уже есть
 * в базе, переиспользуется, отсутствующий заводится без почты и пароля — войти в него нельзя,
 * пока владелец не зарегистрируется.
 *
 * <p>Восстановление рядом с существующей игрой (`asNewGame`) переносит все идентификаторы
 * снимка на новые: иначе вставка упёрлась бы в первичные ключи оригинала. Ссылки
 * переписываются и в колонках, и внутри `jsonb` — там идентификаторы лежат строками.
 */
@Service
public class GameSnapshotRestorer {

    /** Колонки со ссылкой на пользователя: их значения заменяются на локальные аккаунты. */
    private static final Map<String, String> USER_COLUMNS = Map.of(
            "game", "master_user_id",
            "membership", "user_id",
            "message", "author_user_id",
            "game_event", "actor_user_id");

    /**
     * Колонки со ссылкой на строку снимка. Перечислены явно, а не выведены из схемы: у
     * `subject_id`, `target_id` и `created_by` внешнего ключа нет, а замена «любой строки,
     * похожей на UUID» испортила бы текст сообщения, случайно совпавший с идентификатором.
     */
    private static final Map<String, Set<String>> REFERENCE_COLUMNS = Map.ofEntries(
            Map.entry("game", Set.of("final_chat_id")),
            Map.entry("membership", Set.of("reserved_character_id")),
            Map.entry("chat", Set.of("owner_membership_id")),
            Map.entry("chat_member", Set.of("chat_id", "subject_id", "invited_by")),
            Map.entry("transition", Set.of("from_chat_id", "to_chat_id")),
            Map.entry("transition_state", Set.of("transition_id", "character_id")),
            Map.entry("character", Set.of("occupant_membership_id")),
            Map.entry("presence", Set.of("character_id", "chat_id")),
            Map.entry("restriction", Set.of("subject_id", "created_by")),
            Map.entry("storage", Set.of("chat_id", "character_id")),
            Map.entry("item_placement", Set.of("item_id", "storage_id")),
            Map.entry("message", Set.of("chat_id", "character_id", "author_membership_id")),
            Map.entry("inbox_entry", Set.of("subject_id", "chat_id", "message_id")),
            Map.entry("read_cursor", Set.of("subject_id", "chat_id")),
            Map.entry("note", Set.of("author_membership_id", "target_id")),
            Map.entry("note_keyword", Set.of("note_id")),
            Map.entry("saved_message", Set.of("owner_membership_id", "message_id", "note_id")),
            Map.entry("turn", Set.of("closed_by")),
            Map.entry("turn_budget", Set.of("turn_id", "character_id")),
            Map.entry("game_event", Set.of("chat_id", "character_id")));

    /** Колонки `jsonb`: без приведения драйвер записал бы строку в текстовую колонку. */
    private static final Set<String> JSON_COLUMNS = Set.of(
            "message.payload",
            "message.matched_note_ids",
            "game_event.payload");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final GameRepository games;
    private final AppUserRepository users;

    public GameSnapshotRestorer(
            JdbcTemplate jdbc, ObjectMapper json, GameRepository games, AppUserRepository users) {
        this.jdbc = jdbc;
        this.json = json;
        this.games = games;
        this.users = users;
    }

    @SuppressWarnings("unchecked")
    @Transactional
    public UUID restore(UUID actorUserId, Map<String, Object> document, boolean asNewGame) {
        requireFormat(document);
        Map<String, List<Map<String, Object>>> tables =
                (Map<String, List<Map<String, Object>>>) document.get("tables");
        List<Map<String, Object>> gameRows = tables.get("game");
        if (gameRows == null || gameRows.size() != 1) {
            throw ApiException.badRequest("import_bad_game", "В снимке должна быть ровно одна игра");
        }
        UUID sourceGameId = UUID.fromString(gameRows.get(0).get("id").toString());
        boolean exists = games.findById(sourceGameId).isPresent();
        if (exists && !asNewGame) {
            throw ApiException.conflict(
                    "import_game_exists",
                    "Игра с таким идентификатором уже есть: повторите с asNewGame");
        }

        AppUser actor = users.findById(actorUserId)
                .orElseThrow(() -> ApiException.unauthorized("not_authenticated", "Нужен вход"));
        Map<String, String> userMap = mapUsers(tables.get("app_user"), actor);

        Map<String, String> idMap = exists ? remap(tables) : Map.of();
        UUID gameId = exists ? UUID.fromString(idMap.get(sourceGameId.toString())) : sourceGameId;

        try {
            for (SnapshotTables.TableSpec spec : SnapshotTables.ALL) {
                if ("app_user".equals(spec.name())) {
                    continue;
                }
                insertRows(spec, tables.getOrDefault(spec.name(), List.of()), userMap, idMap);
            }
            // Цикл внешних ключей `membership.reserved_character_id` ↔
            // `character.occupant_membership_id`: обе колонки вставлены пустыми, потому что
            // порядок вставки не может удовлетворить оба ключа сразу. Добор идёт после.
            for (Map<String, Object> character : tables.getOrDefault("character", List.of())) {
                Object occupant = character.get("occupant_membership_id");
                if (occupant != null) {
                    jdbc.update(
                            "UPDATE character SET occupant_membership_id = ? WHERE id = ?",
                            uuid(occupant.toString(), idMap),
                            uuid(character.get("id").toString(), idMap));
                }
            }
            for (Map<String, Object> membership : tables.getOrDefault("membership", List.of())) {
                Object reserved = membership.get("reserved_character_id");
                if (reserved != null) {
                    jdbc.update(
                            "UPDATE membership SET reserved_character_id = ? WHERE id = ?",
                            uuid(reserved.toString(), idMap),
                            uuid(membership.get("id").toString(), idMap));
                }
            }
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict(
                    "import_conflict", "Снимок не лёг в базу: конфликт идентификаторов или ссылок");
        }
        return gameId;
    }

    /**
     * Новые идентификаторы для всех строк снимка, кроме аккаунтов: аккаунты сопоставляются
     * по логину и своих идентификаторов не меняют. Один проход по таблицам, потому что
     * первичные ключи глобальны: копия не может оставить себе ни один.
     */
    private Map<String, String> remap(Map<String, List<Map<String, Object>>> tables) {
        Map<String, String> idMap = new LinkedHashMap<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : tables.entrySet()) {
            if ("app_user".equals(entry.getKey())) {
                continue;
            }
            for (Map<String, Object> row : entry.getValue()) {
                Object id = row.get("id");
                if (id != null) {
                    idMap.put(id.toString(), Uuids.v7().toString());
                }
            }
        }
        return idMap;
    }

    /**
     * Аккаунты снимка → аккаунты базы. Существующий логин переиспользуется, новый заводится
     * с тем же идентификатором: тогда остальные таблицы не нужно переприсваивать.
     */
    private Map<String, String> mapUsers(List<Map<String, Object>> rows, AppUser actor) {
        if (rows == null) {
            throw ApiException.badRequest("import_bad_users", "В снимке нет списка пользователей");
        }
        Map<String, String> map = new LinkedHashMap<>();
        boolean actorIsMaster = false;
        for (Map<String, Object> row : rows) {
            String exportedId = row.get("id").toString();
            String normalized = row.get("login_normalized").toString();
            Optional<AppUser> existing = users.findByLoginNormalized(normalized);
            if (existing.isPresent()) {
                map.put(exportedId, existing.get().id().toString());
            } else {
                insertUser(row);
                map.put(exportedId, exportedId);
            }
            if (normalized.equals(actor.loginNormalized())) {
                actorIsMaster = true;
            }
        }
        if (!actorIsMaster) {
            throw ApiException.forbidden(
                    "import_master_only",
                    "Восстанавливать игру может только мастер, указанный в снимке");
        }
        return map;
    }

    private void insertUser(Map<String, Object> row) {
        jdbc.update(
                "INSERT INTO app_user (id, login, login_normalized, display_name, theme, status, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.fromString(row.get("id").toString()),
                row.get("login"),
                row.get("login_normalized"),
                row.get("display_name"),
                row.get("theme"),
                row.get("status"),
                SnapshotRows.toColumn(row.get("created_at"), "timestamp with time zone"),
                SnapshotRows.toColumn(row.get("updated_at"), "timestamp with time zone"));
    }

    private void insertRows(
            SnapshotTables.TableSpec spec,
            List<Map<String, Object>> rows,
            Map<String, String> userMap,
            Map<String, String> idMap) {
        if (rows.isEmpty()) {
            return;
        }
        Map<String, String> types = columnTypes(spec.name());
        String userColumn = USER_COLUMNS.get(spec.name());
        Set<String> references = REFERENCE_COLUMNS.getOrDefault(spec.name(), Set.of());

        List<String> columns = new ArrayList<>();
        for (String column : spec.columns()) {
            // Обе колонки цикла заполняются после вставки: сейчас ключа второй стороны ещё нет.
            if (("character".equals(spec.name()) && "occupant_membership_id".equals(column))
                    || ("membership".equals(spec.name()) && "reserved_character_id".equals(column))) {
                continue;
            }
            columns.add(column);
        }
        String placeholders = String.join(", ", columns.stream()
                .map(column -> JSON_COLUMNS.contains(spec.name() + "." + column) ? "?::jsonb" : "?")
                .toList());
        String sql = "INSERT INTO " + spec.name() + " (" + String.join(", ", columns) + ") VALUES ("
                + placeholders + ")";

        List<Object[]> batch = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object[] values = new Object[columns.size()];
            for (int i = 0; i < columns.size(); i++) {
                String column = columns.get(i);
                Object value = row.get(column);
                boolean isJson = JSON_COLUMNS.contains(spec.name() + "." + column);

                if (value != null && column.equals(userColumn)) {
                    value = userMap.getOrDefault(value.toString(), value.toString());
                } else if (value != null
                        && ("id".equals(column) || "game_id".equals(column)
                            || references.contains(column))) {
                    value = uuid(value.toString(), idMap);
                }
                values[i] = isJson
                        ? asJson(rewriteJson(value, idMap, userMap))
                        : SnapshotRows.toColumn(value, types.get(column));
            }
            batch.add(values);
        }
        jdbc.batchUpdate(sql, batch);
    }

    /**
     * Переписывает идентификаторы внутри `jsonb`: в `payload` и `matched_note_ids` они лежат
     * строками, и без этого копия ссылалась бы на строки оригинала.
     */
    private Object rewriteJson(Object value, Map<String, String> idMap, Map<String, String> userMap) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(
                        entry.getKey().toString(),
                        rewriteJson(entry.getValue(), idMap, userMap));
            }
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            for (Object element : list) {
                result.add(rewriteJson(element, idMap, userMap));
            }
            return result;
        }
        if (value instanceof String text) {
            String mapped = idMap.getOrDefault(text, userMap.get(text));
            return mapped == null ? text : mapped;
        }
        return value;
    }

    private static UUID uuid(String value, Map<String, String> idMap) {
        return UUID.fromString(idMap.getOrDefault(value, value));
    }

    private Map<String, String> columnTypes(String table) {
        Map<String, String> types = new LinkedHashMap<>();
        jdbc.query(
                "SELECT column_name, data_type FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = ?",
                rs -> {
                    types.put(rs.getString("column_name"), rs.getString("data_type"));
                },
                table);
        return types;
    }

    private String asJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw ApiException.badRequest("import_bad_json", "Значение снимка не сериализуется");
        }
    }

    private static void requireFormat(Map<String, Object> document) {
        if (!GameExportService.FORMAT.equals(document.get("format"))) {
            throw ApiException.badRequest(
                    "import_unknown_format", "Это не снимок игры ChatterGame");
        }
        Object version = document.get("version");
        if (!(version instanceof Number number) || number.intValue() != GameExportService.VERSION) {
            throw ApiException.badRequest(
                    "import_unsupported_version", "Версия снимка не поддерживается");
        }
        if (!(document.get("tables") instanceof Map<?, ?>)) {
            throw ApiException.badRequest("import_bad_tables", "В снимке нет таблиц");
        }
    }
}
