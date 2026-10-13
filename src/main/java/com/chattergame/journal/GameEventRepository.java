package com.chattergame.journal;

import com.chattergame.util.Uuids;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/**
 * Журнал append-only: здесь есть чтение и вставка, и нет обновлений и удалений содержимого
 * (`D-033`). Единственная разрешённая запись мимо вставки события — счётчик `game_stream`;
 * появление `UPDATE game_event` или `DELETE FROM game_event` обязано ломать гейт.
 *
 * <p>Номер выдаётся под `SELECT … FOR UPDATE` на строке счётчика, а не через `max(seq) + 1`:
 * между чтением максимума и вставкой успевает вклиниться параллельная мутация, и два события
 * получают один номер. Проверяется тестом на десяти потоках.
 */
@Repository
public class GameEventRepository {

    private static final String COLUMNS =
            "id, game_id, seq, type, chat_id, actor_user_id, character_id, on_behalf_of_master, "
                    + "payload, created_at";

    /**
     * Маппер не статический: разбор `payload` идёт через бин {@link Payloads}, а не через
     * собственный `ObjectMapper`, чтобы настройки JSON были одни на приложение.
     */
    private final RowMapper<GameEvent> mapper = this::map;

    private final JdbcTemplate jdbc;
    private final Payloads payloads;

    public GameEventRepository(JdbcTemplate jdbc, Payloads payloads) {
        this.jdbc = jdbc;
        this.payloads = payloads;
    }

    /** Строка счётчика создаётся вместе с игрой: тогда выдача номера — это только блокировка. */
    public void createStream(UUID gameId) {
        jdbc.update(
                "INSERT INTO game_stream (game_id, last_seq) VALUES (?, 0) ON CONFLICT (game_id) DO NOTHING",
                gameId);
    }

    /**
     * Запись события. Возвращает выданный `seq`. Вызывается только из
     * {@code GameMutationService} внутри транзакции мутации: событие и эффект обязаны
     * фиксироваться вместе, иначе журнал перестаёт быть источником истины (`D-033`, `D-034`).
     */
    public long append(
            UUID gameId,
            GameEventType type,
            UUID actorUserId,
            UUID chatId,
            UUID characterId,
            boolean onBehalfOfMaster,
            String payloadJson) {
        long seq = nextSeq(gameId);
        jdbc.update(
                "INSERT INTO game_event (id, game_id, seq, type, chat_id, actor_user_id, character_id, "
                        + "on_behalf_of_master, payload) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)",
                Uuids.v7(),
                gameId,
                seq,
                type.name(),
                chatId,
                actorUserId,
                characterId,
                onBehalfOfMaster,
                payloadJson);
        jdbc.update("UPDATE game_stream SET last_seq = ? WHERE game_id = ?", seq, gameId);
        return seq;
    }

    /** Номер события выдаётся только под блокировкой строки счётчика: иначе дыры и дубли. */
    private long nextSeq(UUID gameId) {
        jdbc.update(
                "INSERT INTO game_stream (game_id, last_seq) VALUES (?, 0) ON CONFLICT (game_id) DO NOTHING",
                gameId);
        Long lastSeq = jdbc.queryForObject(
                "SELECT last_seq FROM game_stream WHERE game_id = ? FOR UPDATE", Long.class, gameId);
        return (lastSeq == null ? 0L : lastSeq) + 1L;
    }

    /**
     * Аудит-чтение (`PLAN` E03, задача 7): страница журнала по возрастанию `seq` с фильтром
     * по типу. Условие по `seq` — не `offset`: клиент догружает от последнего увиденного
     * события, и `offset` на растущем журнале пропускает и повторяет строки (`D-082`).
     */
    public List<GameEvent> pageAfter(UUID gameId, long afterSeq, GameEventType type, int limit) {
        if (type == null) {
            return jdbc.query(
                    "SELECT " + COLUMNS + " FROM game_event WHERE game_id = ? AND seq > ? "
                            + "ORDER BY seq LIMIT ?",
                    mapper,
                    gameId,
                    afterSeq,
                    limit);
        }
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM game_event WHERE game_id = ? AND seq > ? AND type = ? "
                        + "ORDER BY seq LIMIT ?",
                mapper,
                gameId,
                afterSeq,
                type.name(),
                limit);
    }

    /**
     * Одно событие по номеру. Нужно рассылке (`E09`): уведомление несёт только ссылку
     * `(game_id, seq)`, тело читается здесь (`D-084`).
     */
    public java.util.Optional<GameEvent> find(UUID gameId, long seq) {
        return jdbc.query(
                        "SELECT " + COLUMNS + " FROM game_event WHERE game_id = ? AND seq = ?",
                        mapper,
                        gameId,
                        seq)
                .stream()
                .findFirst();
    }

    public long lastSeq(UUID gameId) {
        Long seq = jdbc.queryForObject(
                "SELECT last_seq FROM game_stream WHERE game_id = ?", Long.class, gameId);
        return seq == null ? 0L : seq;
    }

    public long countByGame(UUID gameId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM game_event WHERE game_id = ?", Long.class, gameId);
        return count == null ? 0L : count;
    }

    private GameEvent map(ResultSet rs, int rowNum) throws SQLException {
        return new GameEvent(
                rs.getObject("id", UUID.class),
                rs.getObject("game_id", UUID.class),
                rs.getLong("seq"),
                GameEventType.valueOf(rs.getString("type")),
                rs.getObject("chat_id", UUID.class),
                rs.getObject("actor_user_id", UUID.class),
                rs.getObject("character_id", UUID.class),
                rs.getBoolean("on_behalf_of_master"),
                payloads.read(rs.getString("payload")),
                rs.getTimestamp("created_at").toInstant());
    }
}
