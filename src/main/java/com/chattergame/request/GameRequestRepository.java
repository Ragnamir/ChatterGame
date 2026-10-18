package com.chattergame.request;

import com.chattergame.journal.Payloads;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Доступ к `request`. Строка не удаляется: заявка — часть истории правок, и `D-010` запрещает
 * удаление доменных строк. Отклонённая и просроченная заявка остаются читаемыми — иначе
 * мастер не отличит «предложение не приходило» от «пришло и не понравилось».
 *
 * <p>Идемпотентность держит уникальный индекс `ux_request_idempotency` по
 * `(game_id, idempotency_key)` из baseline, а не проверка «сначала прочитай, потом запиши»:
 * повтор запроса приходит от ретрая сети, и два параллельных повтора проходят проверку
 * одновременно. Поэтому вставка разрешает конфликт на стороне базы и сообщает вызывающему,
 * что строки не появилось.
 */
@Repository
public class GameRequestRepository {

    private static final String COLUMNS =
            "id, game_id, origin, type, from_membership_id, to_character_id, payload, status, "
                    + "error, idempotency_key, created_at, updated_at, expires_at";

    private final JdbcTemplate jdbc;
    private final Payloads payloads;
    private final RowMapper<GameRequest> mapper = this::map;

    public GameRequestRepository(JdbcTemplate jdbc, Payloads payloads) {
        this.jdbc = jdbc;
        this.payloads = payloads;
    }

    /**
     * Вставка заявки. Возвращает `false`, если такая пара `(game_id, idempotency_key)` уже
     * есть: вызывающий читает существующую строку и отвечает ею же (`PLAN` E14, задача 6).
     *
     * <p>`ON CONFLICT (game_id, idempotency_key)`, а не перехват ошибки-дубликата: в PostgreSQL
     * неудачная вставка отравляет транзакцию до её конца, и поймать конфликт, чтобы продолжить
     * работу в той же транзакции, нельзя — следующая же инструкция упала бы с «current
     * transaction is aborted». Разрешение конфликта базой оставляет транзакцию живой. Цель
     * названа колонками, а не «любой конфликт»: `ON CONFLICT` без цели поймал бы и совпадение
     * первичного ключа, скрыв настоящую ошибку.
     */
    public boolean insert(GameRequest request) {
        int inserted = jdbc.update(
                "INSERT INTO request (id, game_id, origin, type, from_membership_id, "
                        + "to_character_id, payload, status, error, idempotency_key, created_at, "
                        + "updated_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (game_id, idempotency_key) DO NOTHING",
                request.id(),
                request.gameId(),
                request.origin().name(),
                request.type().name(),
                request.fromMembershipId(),
                request.toCharacterId(),
                payloads.write(request.payload()),
                request.status().name(),
                request.error(),
                request.idempotencyKey(),
                Timestamp.from(request.createdAt()),
                Timestamp.from(request.updatedAt()),
                request.expiresAt() == null ? null : Timestamp.from(request.expiresAt()));
        return inserted > 0;
    }

    public Optional<GameRequest> findById(UUID gameId, UUID id) {
        return jdbc.query(
                        "SELECT " + COLUMNS + " FROM request WHERE game_id = ? AND id = ?",
                        mapper,
                        gameId,
                        id)
                .stream()
                .findFirst();
    }

    public Optional<GameRequest> findByIdempotencyKey(UUID gameId, String key) {
        return jdbc.query(
                        "SELECT " + COLUMNS + " FROM request WHERE game_id = ? AND idempotency_key = ?",
                        mapper,
                        gameId,
                        key)
                .stream()
                .findFirst();
    }

    /** Заявки игры: мастеру — все, игроку — свои (`REQUIREMENTS §17`). Фильтр по статусу необязателен. */
    public List<GameRequest> findByGame(UUID gameId, RequestStatus status) {
        if (status == null) {
            return jdbc.query(
                    "SELECT " + COLUMNS + " FROM request WHERE game_id = ? ORDER BY created_at, id",
                    mapper,
                    gameId);
        }
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM request WHERE game_id = ? AND status = ? "
                        + "ORDER BY created_at, id",
                mapper,
                gameId,
                status.name());
    }

    public List<GameRequest> findByAuthor(UUID gameId, UUID membershipId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM request WHERE game_id = ? AND from_membership_id = ? "
                        + "ORDER BY created_at, id",
                mapper,
                gameId,
                membershipId);
    }

    public void updateStatus(
            UUID gameId, UUID id, RequestStatus status, String error, Instant updatedAt) {
        jdbc.update(
                "UPDATE request SET status = ?, error = ?, updated_at = ? WHERE game_id = ? AND id = ?",
                status.name(),
                error,
                Timestamp.from(updatedAt),
                gameId,
                id);
    }

    /**
     * Просроченные заявки, ждущие решения. Предикат — ровно `PENDING` и `SUCCEEDED`: это
     * тот же предикат, что у частичного индекса `ix_request_awaiting_expires`
     * (`010-f05.yaml`), и совпадение здесь не косметическое — расхождение предиката и
     * индекса превращает выборку в обход всей таблицы.
     *
     * <p>`SUCCEEDED` — предложение модели, которое дождалось ответа и ждёт мастера (`D-043`):
     * срок жизни у него тот же, что у просьбы игрока, иначе такие строки копились бы вечно.
     * `RUNNING` не входит и не должен: это заявка, которую прямо сейчас исполняет вызов
     * модели, и срок её жизни не конкурирует с исполнением.
     */
    public List<GameRequest> findExpired(Instant now) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM request WHERE status IN ('PENDING', 'SUCCEEDED') "
                        + "AND expires_at IS NOT NULL AND expires_at <= ? ORDER BY expires_at",
                mapper,
                Timestamp.from(now));
    }

    private GameRequest map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp expiresAt = rs.getTimestamp("expires_at");
        Map<String, Object> payload = payloads.read(rs.getString("payload"));
        return new GameRequest(
                rs.getObject("id", UUID.class),
                rs.getObject("game_id", UUID.class),
                RequestOrigin.valueOf(rs.getString("origin")),
                RequestType.valueOf(rs.getString("type")),
                rs.getObject("from_membership_id", UUID.class),
                rs.getObject("to_character_id", UUID.class),
                payload,
                RequestStatus.valueOf(rs.getString("status")),
                rs.getString("error"),
                rs.getString("idempotency_key"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                expiresAt == null ? null : expiresAt.toInstant());
    }
}
