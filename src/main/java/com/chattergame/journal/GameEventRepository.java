package com.chattergame.journal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Заглушка чтения журнала. Журнал append-only: здесь допустимы только чтение и вставка,
 * обновлений и удалений быть не может (`D-033`). Появление `UPDATE game_event` или
 * `DELETE FROM game_event` обязано ломать гейт.
 */
@Repository
public class GameEventRepository {

    private final JdbcTemplate jdbcTemplate;

    public GameEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long lastSeq(long gameId) {
        Long seq = jdbcTemplate.queryForObject(
                "SELECT coalesce(max(seq), 0) FROM game_event WHERE game_id = ?", Long.class, gameId);
        return seq == null ? 0L : seq;
    }

    public long countByGame(long gameId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM game_event WHERE game_id = ?", Long.class, gameId);
        return count == null ? 0L : count;
    }
}
