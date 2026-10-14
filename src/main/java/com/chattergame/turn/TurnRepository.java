package com.chattergame.turn;

import com.chattergame.util.Uuids;
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
 * Доступ к `turn` и `turn_budget`. SQL хода живёт только здесь; сервис не собирает
 * запросов, а берёт готовые операции (`D-034` — изменение мира в одном слое).
 *
 * <p>Удаления нет: строка хода и бюджет закрываются статусом и флагами (`D-010`).
 *
 * <p>Две блокировки вынесены наружу осознанно. {@link #lockOpenForShare} берётся при
 * расходе бюджета: она совместима с параллельными расходами разных персонажей и
 * конфликтует с {@code FOR UPDATE} из {@link #lockForUpdate}, которым мастер закрывает
 * ход, — поэтому сообщение, заставшее смену хода, не «просачивается» в закрытый ход.
 * {@link #lockBudget} сериализует двух писателей одного персонажа в разных чатах: без
 * неё суммарный лимит (`D-013`) проверялся бы дважды по одному и тому же «пусто».
 */
@Repository
public class TurnRepository {

    private static final String COLUMNS = "id, game_id, n, status, opened_at, closed_by, closed_at";

    private final JdbcTemplate jdbc;
    private final RowMapper<Turn> mapper = this::map;

    public TurnRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Открытие хода. Момент открытия проставляет база (`opened_at` по умолчанию `now()`):
     * приложение не приносит своё представление о времени, иначе две ноды разошлись бы
     * в оценке «когда ход открыт».
     */
    public void openTurn(UUID id, UUID gameId, long n) {
        jdbc.update(
                "INSERT INTO turn (id, game_id, n, status) VALUES (?, ?, ?, 'OPEN')",
                id,
                gameId,
                n);
    }

    /** Закрытие открытого хода. Повторный вызов не трогает уже закрытую строку. */
    public void close(UUID turnId, UUID closedBy) {
        jdbc.update(
                "UPDATE turn SET status = 'CLOSED', closed_by = ?, closed_at = now() "
                        + "WHERE id = ? AND status = 'OPEN'",
                closedBy,
                turnId);
    }

    public Optional<Turn> findOpen(UUID gameId) {
        return find("SELECT " + COLUMNS + " FROM turn WHERE game_id = ? AND status = 'OPEN'", gameId);
    }

    /** Последний ход игры по номеру: он же текущий, если открыт, и им же отвечает чтение. */
    public Optional<Turn> findLatest(UUID gameId) {
        return find("SELECT " + COLUMNS + " FROM turn WHERE game_id = ? ORDER BY n DESC LIMIT 1", gameId);
    }

    public long maxNumber(UUID gameId) {
        Long max = jdbc.queryForObject(
                "SELECT max(n) FROM turn WHERE game_id = ?", Long.class, gameId);
        return max == null ? 0L : max;
    }

    /**
     * Открытый ход под разделяемой блокировкой. Если параллельная транзакция успела его
     * закрыть и зафиксировалась, строка перестаёт подходить под условие и не возвращается:
     * расход бюджета в закрытом ходу обязан быть отклонён, а не применён к прошлому ходу.
     */
    public Optional<Turn> lockOpenForShare(UUID gameId) {
        return find("SELECT " + COLUMNS + " FROM turn WHERE game_id = ? AND status = 'OPEN' FOR SHARE", gameId);
    }

    /**
     * Бюджет персонажа в ходу под исключительной блокировкой. Вставка идемпотентна:
     * строку создаёт первый расход, а второй писатель того же персонажа дожидается
     * блокировки и видит уже проставленный флаг.
     */
    public TurnBudget lockBudget(UUID turnId, UUID characterId) {
        jdbc.update(
                "INSERT INTO turn_budget (id, turn_id, character_id) VALUES (?, ?, ?) "
                        + "ON CONFLICT (turn_id, character_id) DO NOTHING",
                Uuids.v7(),
                turnId,
                characterId);
        return jdbc.query(
                        "SELECT id, character_id, action_used, speech_used FROM turn_budget "
                                + "WHERE turn_id = ? AND character_id = ? FOR UPDATE",
                        (rs, rowNum) -> new TurnBudget(
                                rs.getObject("id", UUID.class),
                                rs.getObject("character_id", UUID.class),
                                rs.getBoolean("action_used"),
                                rs.getBoolean("speech_used")),
                        turnId,
                        characterId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Бюджет хода не создан: " + turnId));
    }

    /** Оба флага пишутся целиком: частичная запись разошлась бы с тем, что видит чтение. */
    public void updateUsage(TurnBudget budget) {
        jdbc.update(
                "UPDATE turn_budget SET action_used = ?, speech_used = ? WHERE id = ?",
                budget.actionUsed(),
                budget.speechUsed(),
                budget.id());
    }

    public List<TurnBudget> findBudgets(UUID turnId) {
        return jdbc.query(
                "SELECT id, character_id, action_used, speech_used FROM turn_budget "
                        + "WHERE turn_id = ? ORDER BY character_id",
                (rs, rowNum) -> new TurnBudget(
                        rs.getObject("id", UUID.class),
                        rs.getObject("character_id", UUID.class),
                        rs.getBoolean("action_used"),
                        rs.getBoolean("speech_used")),
                turnId);
    }

    private Optional<Turn> find(String sql, UUID gameId) {
        return jdbc.query(sql, mapper, gameId).stream().findFirst();
    }

    private Turn map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp closedAt = rs.getTimestamp("closed_at");
        return new Turn(
                rs.getObject("id", UUID.class),
                rs.getObject("game_id", UUID.class),
                rs.getLong("n"),
                TurnStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("opened_at").toInstant(),
                rs.getObject("closed_by", UUID.class),
                closedAt == null ? null : closedAt.toInstant());
    }
}
