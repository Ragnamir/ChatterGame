package com.chattergame.game;

import com.chattergame.util.Uuids;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Архивирование игр мастера. Единственная причина существования на `E02`: удаление аккаунта
 * мастера архивирует его игры, но оставляет их читаемыми участникам (`D-095`, передача
 * мастерства запрещена `D-021`).
 *
 * <p>Мутация идёт ровно так, как требует журнал: номер выдаётся под `SELECT … FOR UPDATE`
 * на строке `game_stream`, событие пишется, и только потом меняется игра (`D-033`, `D-034`).
 * При полноценном `GameMutationService` (`E03`) этот класс сведётся к одному вызову, но
 * правило «мутация оставляет событие» соблюдается уже здесь, а не обходится прямым UPDATE.
 */
@Service
public class GameArchiveService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public GameArchiveService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public int archiveAllOfMaster(UUID masterUserId, String reason) {
        List<UUID> gameIds = jdbc.queryForList(
                "SELECT id FROM game WHERE master_user_id = ? AND archived = false",
                UUID.class,
                masterUserId);

        String payload = payload(reason, masterUserId);
        for (UUID gameId : gameIds) {
            long seq = nextSeq(gameId);
            jdbc.update(
                    "INSERT INTO game_event (id, game_id, seq, type, actor_user_id, payload) "
                            + "VALUES (?, ?, ?, 'GAME_ARCHIVED', ?, ?::jsonb)",
                    Uuids.v7(),
                    gameId,
                    seq,
                    masterUserId,
                    payload);
            jdbc.update("UPDATE game_stream SET last_seq = ? WHERE game_id = ?", seq, gameId);
            jdbc.update(
                    "UPDATE game SET archived = true, archived_reason = ?, updated_at = now() WHERE id = ?",
                    reason,
                    gameId);
        }
        return gameIds.size();
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

    private String payload(String reason, UUID masterUserId) {
        try {
            return objectMapper.writeValueAsString(
                    Map.of("reason", reason, "masterUserId", masterUserId.toString()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Не удалось собрать payload события архивации", e);
        }
    }
}
