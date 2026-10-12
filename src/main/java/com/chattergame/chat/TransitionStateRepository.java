package com.chattergame.chat;

import com.chattergame.util.Uuids;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Доступ к `transition_state` — знанию ребра персонажем. Строки не удаляются: возврат к
 * {@link EdgeState#UNKNOWN} пишется значением, потому что удаление доменных строк запрещено
 * (`D-010`, инвариант `physicalDeletions`).
 *
 * <p>Запись — `upsert` по уникальному `(transition_id, character_id)`: выдача знания
 * повторно не должна падать на конфликте, мастер может нажать кнопку дважды.
 */
@Repository
public class TransitionStateRepository {

    private static final String COLUMNS = "id, transition_id, character_id, state, reason";

    private final JdbcTemplate jdbc;
    private final RowMapper<TransitionState> mapper = this::map;

    public TransitionStateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public TransitionState upsert(UUID transitionId, UUID characterId, EdgeState state, String reason) {
        UUID id = Uuids.v7();
        jdbc.update(
                "INSERT INTO transition_state (id, transition_id, character_id, state, reason) "
                        + "VALUES (?, ?, ?, ?, ?) "
                        + "ON CONFLICT (transition_id, character_id) "
                        + "DO UPDATE SET state = EXCLUDED.state, reason = EXCLUDED.reason",
                id,
                transitionId,
                characterId,
                state.name(),
                reason);
        return find(transitionId, characterId).orElseThrow();
    }

    public Optional<TransitionState> find(UUID transitionId, UUID characterId) {
        return jdbc.query(
                        "SELECT " + COLUMNS + " FROM transition_state "
                                + "WHERE transition_id = ? AND character_id = ?",
                        mapper,
                        transitionId,
                        characterId)
                .stream()
                .findFirst();
    }

    /** Состояния всех рёбер игры, известные хотя бы одному персонажу; нужны графу мастера. */
    public List<TransitionState> findByTransition(UUID transitionId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM transition_state WHERE transition_id = ? ORDER BY character_id",
                mapper,
                transitionId);
    }

    /**
     * Состояния рёбер, известные конкретному персонажу. `UNKNOWN` сюда попадает наравне с
     * остальными: фильтрация «не отдавать неизвестное» живёт в графе, а не в SQL, иначе
     * правило расползлось бы по всем запросам (`D-030`).
     */
    public List<TransitionState> findByCharacter(UUID characterId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM transition_state WHERE character_id = ?",
                mapper,
                characterId);
    }

    private TransitionState map(ResultSet rs, int rowNum) throws SQLException {
        return new TransitionState(
                rs.getObject("id", UUID.class),
                rs.getObject("transition_id", UUID.class),
                rs.getObject("character_id", UUID.class),
                EdgeState.valueOf(rs.getString("state")),
                rs.getString("reason"));
    }
}
