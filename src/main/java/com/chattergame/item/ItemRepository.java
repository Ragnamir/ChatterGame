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
 * Доступ к `item` и `item_placement`. Предмет не удаляется и не обнуляется (`D-010`, `D-114`):
 * взятая часть стопки отделяется новой строкой, полное взятие переезжает `UPDATE`-ом.
 *
 * <p>Блокировка строки предмета живёт здесь: она часть способа доступа, а не решения
 * вызывающего (`D-015`).
 */
@Repository
public class ItemRepository {

    private static final String COLUMNS =
            "id, game_id, type, name, description, image_asset_id, size, quantity, properties, "
                    + "created_at, updated_at";

    private final JdbcTemplate jdbc;
    private final RowMapper<Item> mapper = this::map;

    public ItemRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Item item, UUID storageId) {
        jdbc.update(
                "INSERT INTO item (id, game_id, type, name, description, image_asset_id, size, "
                        + "quantity, properties, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                item.id(),
                item.gameId(),
                item.type().name(),
                item.name(),
                item.description(),
                item.imageAssetId(),
                item.size().name(),
                item.quantity(),
                item.properties(),
                Timestamp.from(item.createdAt()),
                Timestamp.from(item.updatedAt()));
        jdbc.update(
                "INSERT INTO item_placement (id, item_id, storage_id, placed_at) VALUES (?, ?, ?, now())",
                UUID.randomUUID(),
                item.id(),
                storageId);
    }

    public Optional<Item> findById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM item WHERE id = ?", mapper, id)
                .stream()
                .findFirst();
    }

    /**
     * Предмет с блокировкой строки (`D-015`). Гонка за предмет разрешается здесь: пока
     * транзакция держит строку, вторая видит уже применённый результат первой.
     */
    public Optional<Item> lockById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM item WHERE id = ? FOR UPDATE", mapper, id)
                .stream()
                .findFirst();
    }

    public List<Item> findByStorage(UUID storageId) {
        return jdbc.query(
                "SELECT i." + COLUMNS.replace(", ", ", i.") + " FROM item i "
                        + "JOIN item_placement p ON p.item_id = i.id "
                        + "WHERE p.storage_id = ? ORDER BY i.created_at, i.id",
                mapper,
                storageId);
    }

    public Optional<ItemPlacement> findPlacement(UUID itemId) {
        return jdbc.query(
                        "SELECT id, item_id, storage_id FROM item_placement WHERE item_id = ?",
                        (rs, rowNum) -> new ItemPlacement(
                                rs.getObject("id", UUID.class),
                                rs.getObject("item_id", UUID.class),
                                rs.getObject("storage_id", UUID.class)),
                        itemId)
                .stream()
                .findFirst();
    }

    /** Переезд: строка размещения меняется, потому что удалять её нельзя (`D-010`). */
    public void movePlacement(UUID itemId, UUID storageId) {
        jdbc.update("UPDATE item_placement SET storage_id = ? WHERE item_id = ?", storageId, itemId);
    }

    public void updateQuantity(UUID itemId, int quantity) {
        jdbc.update("UPDATE item SET quantity = ?, updated_at = now() WHERE id = ?", quantity, itemId);
    }

    public void updateContent(
            UUID itemId,
            ItemType type,
            String name,
            String description,
            ItemSize size,
            int quantity,
            String properties) {
        jdbc.update(
                "UPDATE item SET type = ?, name = ?, description = ?, size = ?, quantity = ?, "
                        + "properties = ?, updated_at = now() WHERE id = ?",
                type.name(),
                name,
                description,
                size.name(),
                quantity,
                properties,
                itemId);
    }

    public void updateImage(UUID itemId, UUID assetId) {
        jdbc.update("UPDATE item SET image_asset_id = ?, updated_at = now() WHERE id = ?", assetId, itemId);
    }

    /** Проверка инварианта «предмет всегда ровно в одном хранилище» для готовности (`D-112`). */
    public List<UUID> itemsWithoutPlacement(UUID gameId) {
        return jdbc.queryForList(
                "SELECT i.id FROM item i LEFT JOIN item_placement p ON p.item_id = i.id "
                        + "WHERE i.game_id = ? AND p.id IS NULL",
                UUID.class,
                gameId);
    }

    private Item map(ResultSet rs, int rowNum) throws SQLException {
        return new Item(
                rs.getObject("id", UUID.class),
                rs.getObject("game_id", UUID.class),
                ItemType.valueOf(rs.getString("type")),
                rs.getString("name"),
                rs.getString("description"),
                rs.getObject("image_asset_id", UUID.class),
                ItemSize.valueOf(rs.getString("size")),
                rs.getInt("quantity"),
                rs.getString("properties"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
