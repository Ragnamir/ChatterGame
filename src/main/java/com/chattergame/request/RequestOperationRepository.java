package com.chattergame.request;

import com.chattergame.journal.Payloads;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Доступ к `request_operation`. Операция — строка истории заявки, а не временная запись:
 * её статус и старое значение нужны после применения, чтобы мастер видел, что применилось,
 * что нет и чем это было раньше.
 *
 * <p>Порядок задаёт `ordinal`, и он же держит уникальность `ux_request_operation_ordinal`:
 * две операции с одним номером сделали бы порядок применения недетерминированным.
 */
@Repository
public class RequestOperationRepository {

    private static final String COLUMNS =
            "id, request_id, ordinal, op, payload, status, old_value";

    private final JdbcTemplate jdbc;
    private final Payloads payloads;
    private final RowMapper<RequestOperation> mapper = this::map;

    public RequestOperationRepository(JdbcTemplate jdbc, Payloads payloads) {
        this.jdbc = jdbc;
        this.payloads = payloads;
    }

    public void insert(RequestOperation operation) {
        jdbc.update(
                "INSERT INTO request_operation (id, request_id, ordinal, op, payload, status, old_value) "
                        + "VALUES (?, ?, ?, ?, ?::jsonb, ?, ?::jsonb)",
                operation.id(),
                operation.requestId(),
                operation.ordinal(),
                operation.op().wireName(),
                payloads.write(operation.payload()),
                operation.statusOf().name(),
                operation.oldValue() == null ? null : payloads.write(operation.oldValue()));
    }

    public List<RequestOperation> findByRequest(UUID requestId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM request_operation WHERE request_id = ? ORDER BY ordinal",
                mapper,
                requestId);
    }

    /** Смена статуса операции вместе со снимком прежнего значения, снятым при применении. */
    public void setStatus(UUID id, OperationStatus status, Map<String, Object> oldValue) {
        jdbc.update(
                "UPDATE request_operation SET status = ?, old_value = ?::jsonb WHERE id = ?",
                status.name(),
                oldValue == null ? null : payloads.write(oldValue),
                id);
    }

    private RequestOperation map(ResultSet rs, int rowNum) throws SQLException {
        String oldValue = rs.getString("old_value");
        String rawOp = rs.getString("op");
        ChangeOperationType op = ChangeOperationType.byWireName(rawOp)
                .orElseThrow(() -> new IllegalStateException(
                        "В request_operation записан неизвестный вид операции: " + rawOp));
        return new RequestOperation(
                rs.getObject("id", UUID.class),
                rs.getObject("request_id", UUID.class),
                rs.getInt("ordinal"),
                op,
                payloads.read(rs.getString("payload")),
                rs.getString("status"),
                oldValue == null ? null : payloads.read(oldValue));
    }
}
