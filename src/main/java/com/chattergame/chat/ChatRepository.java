package com.chattergame.chat;

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
 * Доступ к `chat`. Удаления нет и не будет: локация архивируется с причиной и остаётся
 * читаемой (`D-009`, `D-010`). Методы записи вызываются из
 * {@code GameMutationService}, а не из контроллеров.
 */
@Repository
public class ChatRepository {

    private static final String COLUMNS =
            "id, game_id, kind, title, description, image_asset_id, archived, archived_reason, "
                    + "archived_at, created_at";

    private final JdbcTemplate jdbc;
    private final RowMapper<Chat> mapper = this::map;

    public ChatRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Chat chat) {
        jdbc.update(
                "INSERT INTO chat (id, game_id, kind, title, description, image_asset_id, archived, "
                        + "archived_reason, archived_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                chat.id(),
                chat.gameId(),
                chat.kind().name(),
                chat.title(),
                chat.description(),
                chat.imageAssetId(),
                chat.archived(),
                chat.archivedReason(),
                chat.archivedAt() == null ? null : Timestamp.from(chat.archivedAt()),
                Timestamp.from(chat.createdAt()));
    }

    public Optional<Chat> findById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM chat WHERE id = ?", mapper, id)
                .stream()
                .findFirst();
    }

    /** Локации игры по возрастанию создания: порядок графа не должен зависеть от плана запроса. */
    public List<Chat> findByGame(UUID gameId, ChatKind kind) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM chat WHERE game_id = ? AND kind = ? ORDER BY created_at, id",
                mapper,
                gameId,
                kind.name());
    }

    public void updateContent(UUID id, String title, String description) {
        jdbc.update("UPDATE chat SET title = ?, description = ? WHERE id = ?", title, description, id);
    }

    /**
     * Привязка картинки локации и её снятие одним оператором. `null` — снятие: отдельный
     * «удаляющий» метод выглядел бы как удаление картинки, а удаления здесь нет — меняется
     * указатель (`D-077`).
     */
    public void updateImage(UUID id, UUID assetId) {
        jdbc.update("UPDATE chat SET image_asset_id = ? WHERE id = ?", assetId, id);
    }

    /**
     * Архивирование. Момент ставит БД, а не часы приложения: иначе две ноды разошлись бы
     * в оценке «когда локация вышла из игры».
     */
    public void markArchived(UUID id, String reason) {
        jdbc.update(
                "UPDATE chat SET archived = true, archived_reason = ?, archived_at = now() WHERE id = ?",
                reason,
                id);
    }

    private Chat map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp archivedAt = rs.getTimestamp("archived_at");
        return new Chat(
                rs.getObject("id", UUID.class),
                rs.getObject("game_id", UUID.class),
                ChatKind.valueOf(rs.getString("kind")),
                rs.getString("title"),
                rs.getString("description"),
                rs.getObject("image_asset_id", UUID.class),
                rs.getBoolean("archived"),
                rs.getString("archived_reason"),
                archivedAt == null ? null : archivedAt.toInstant(),
                rs.getTimestamp("created_at").toInstant());
    }
}
