package com.chattergame.character;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Доступ к `presence`. Строки не удаляются, а закрываются полем `left_at` (`D-007`, `D-010`);
 * поэтому «перемещение» — это две записи в одну транзакцию, а не `UPDATE` одной строки.
 * История присутствия нужна журналу и экспорту: пропавшая строка сделала бы транскрипт
 * нечитаемым (`D-009`).
 *
 * <p>Частичный уникальный индекс `ux_presence_active_character` (`003-e06.yaml`) — не
 * украшение: он и есть защита от персонажа, стоящего в двух локациях после двух
 * параллельных перемещений. Индекс ловит это на уровне базы, а не проверкой в сервисе.
 */
@Repository
public class PresenceRepository {

    private static final String COLUMNS =
            "id, character_id, chat_id, hidden, joined_at, left_at";

    private final JdbcTemplate jdbc;
    private final RowMapper<Presence> mapper = this::map;

    public PresenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Presence presence) {
        jdbc.update(
                "INSERT INTO presence (id, character_id, chat_id, hidden, joined_at, left_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                presence.id(),
                presence.characterId(),
                presence.chatId(),
                presence.hidden(),
                Timestamp.from(presence.joinedAt()),
                presence.leftAt() == null ? null : Timestamp.from(presence.leftAt()));
    }

    /** Активное присутствие персонажа. Оно одно: это держит частичный уникальный индекс. */
    public Optional<Presence> findActive(UUID characterId) {
        return jdbc.query(
                        "SELECT " + COLUMNS + " FROM presence "
                                + "WHERE character_id = ? AND left_at IS NULL",
                        mapper,
                        characterId)
                .stream()
                .findFirst();
    }

    /**
     * Присутствующие в локации. `hidden` не фильтруется здесь: кто именно увидит скрытого
     * персонажа, решает читающий слой, а спрятать его в SQL значит размазать правило
     * видимости по всем запросам (`D-030`).
     */
    public List<Presence> findActiveInChat(UUID chatId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM presence "
                        + "WHERE chat_id = ? AND left_at IS NULL ORDER BY joined_at, id",
                mapper,
                chatId);
    }

    /** Закрытие присутствия моментом от базы: две ноды разошлись бы в оценке «когда ушёл». */
    public void close(UUID id) {
        jdbc.update("UPDATE presence SET left_at = now() WHERE id = ?", id);
    }

    /**
     * Активные присутствия всей игры одним запросом. Граф собирается целиком, и запрос
     * присутствующих на каждую локацию отдельно дал бы 50 обращений к базе на один ответ
     * клиенту.
     */
    public List<Presence> findActiveByGame(UUID gameId) {
        return jdbc.query(
                "SELECT p.id, p.character_id, p.chat_id, p.hidden, p.joined_at, p.left_at "
                        + "FROM presence p JOIN chat c ON c.id = p.chat_id "
                        + "WHERE c.game_id = ? AND p.left_at IS NULL ORDER BY p.joined_at, p.id",
                mapper,
                gameId);
    }

    /** Скрытность активного присутствия; история не переписывается — прошлое остаётся как было. */
    public void setHidden(UUID id, boolean hidden) {
        jdbc.update("UPDATE presence SET hidden = ? WHERE id = ?", hidden, id);
    }

    private Presence map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp leftAt = rs.getTimestamp("left_at");
        return new Presence(
                rs.getObject("id", UUID.class),
                rs.getObject("character_id", UUID.class),
                rs.getObject("chat_id", UUID.class),
                rs.getBoolean("hidden"),
                rs.getTimestamp("joined_at").toInstant(),
                leftAt == null ? null : leftAt.toInstant());
    }
}
