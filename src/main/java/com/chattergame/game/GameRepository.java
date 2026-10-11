package com.chattergame.game;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Заглушка доступа к играм. Сущности игр не удаляются никогда, только архивируются
 * с причиной (`D-010`); второй запрос — единственный разрешённый путь «снять с глаз»,
 * он выставляет флаг архивации, а не удаляет строку.
 */
@Repository
public class GameRepository {

    private final JdbcTemplate jdbcTemplate;

    public GameRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long count() {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM game", Long.class);
        return count == null ? 0L : count;
    }

    public void archive(long gameId, String reason) {
        jdbcTemplate.update(
                "UPDATE game SET archived = true, archived_reason = ? WHERE id = ?", reason, gameId);
    }
}
