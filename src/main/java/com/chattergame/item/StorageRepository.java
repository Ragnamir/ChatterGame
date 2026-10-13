package com.chattergame.item;

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
 * Доступ к `storage`. Удаления нет: хранилище — сущность игры, а физическое удаление
 * доменных строк запрещено (`D-010`). Мутации вызывает {@code GameMutationService},
 * а не контроллеры.
 */
@Repository
public class StorageRepository {

    private static final String COLUMNS =
            "id, game_id, kind, chat_id, character_id, title, description, image_asset_id, "
                    + "is_open, created_at";

    private final JdbcTemplate jdbc;
    private final RowMapper<Storage> mapper = this::map;

    public StorageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Storage storage) {
        jdbc.update(
                "INSERT INTO storage (id, game_id, kind, chat_id, character_id, title, description, "
                        + "image_asset_id, is_open, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                storage.id(),
                storage.gameId(),
                storage.kind().name(),
                storage.chatId(),
                storage.characterId(),
                storage.title(),
                storage.description(),
                storage.imageAssetId(),
                storage.open(),
                Timestamp.from(storage.createdAt()));
    }

    public Optional<Storage> findById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM storage WHERE id = ?", mapper, id)
                .stream()
                .findFirst();
    }

    /** Хранилища одной игры в порядке создания: порядок ответа не должен зависеть от плана запроса. */
    public List<Storage> findByGame(UUID gameId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM storage WHERE game_id = ? ORDER BY created_at, id",
                mapper,
                gameId);
    }

    /** Хранилища локационного чата: без них `LOCATION` и `CHEST` было бы негде взять. */
    public List<Storage> findByChat(UUID chatId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM storage WHERE chat_id = ? ORDER BY created_at, id",
                mapper,
                chatId);
    }

    /** Оба хранилища персонажа: мелкое и крупное (`REQUIREMENTS §10`). */
    public List<Storage> findByCharacter(UUID characterId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM storage WHERE character_id = ? ORDER BY created_at, id",
                mapper,
                characterId);
    }

    public Optional<Storage> findByCharacterAndKind(UUID characterId, StorageKind kind) {
        return jdbc.query(
                        "SELECT " + COLUMNS + " FROM storage WHERE character_id = ? AND kind = ?",
                        mapper,
                        characterId,
                        kind.name())
                .stream()
                .findFirst();
    }

    public void updateContent(UUID id, String title, String description) {
        jdbc.update("UPDATE storage SET title = ?, description = ? WHERE id = ?", title, description, id);
    }

    /** Указатель на картинку переключается, а не «удаляется»: удаления картинок здесь нет (`D-077`). */
    public void updateImage(UUID id, UUID assetId) {
        jdbc.update("UPDATE storage SET image_asset_id = ? WHERE id = ?", assetId, id);
    }

    /**
     * Открытие и закрытие одним оператором. Отдельный метод на закрытие выглядел бы как
     * удаление содержимого, а хранилище не удаляет ничего — меняется доступ (`D-110`).
     */
    public void updateOpen(UUID id, boolean open) {
        jdbc.update("UPDATE storage SET is_open = ? WHERE id = ?", open, id);
    }

    private Storage map(ResultSet rs, int rowNum) throws SQLException {
        return new Storage(
                rs.getObject("id", UUID.class),
                rs.getObject("game_id", UUID.class),
                StorageKind.valueOf(rs.getString("kind")),
                rs.getObject("chat_id", UUID.class),
                rs.getObject("character_id", UUID.class),
                rs.getString("title"),
                rs.getString("description"),
                rs.getObject("image_asset_id", UUID.class),
                rs.getBoolean("is_open"),
                rs.getTimestamp("created_at").toInstant());
    }
}
