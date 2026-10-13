package com.chattergame.media;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Доступ к `asset`.
 *
 * <p><b>Единственное место, где удаляются доменные строки.</b> Узкое исключение из `D-010`
 * выдано решением `D-104` ровно этому классу и ровно одному оператору — {@link #purge}:
 * орфан-сборка обязана освобождать диск, а `D-077` требует именно удаления. Правило гейта
 * (`E01InvariantsTest`, `E05DeletionTest`) знает про это исключение и краснеет, если
 * `DELETE FROM asset` появится в другом файле.
 *
 * <p>Знание всех ссылок на ассет сосредоточено здесь же: сборщик, который не видит хотя бы
 * одну колонку-ссылку, удаляет живые картинки, поэтому список колонок — часть контракта
 * класса, а не деталь запроса.
 */
@Repository
public class AssetRepository {

    /**
     * Колонки, из которых на ассет может ссылаться игровой мир. Хранилище добавило свою
     * ссылку на `E07` вместе с колонкой (`D-106`); `item.image_asset_id` в baseline уже был
     * и в предикате стоял с самого начала.
     */
    private static final String REFERENCE_PREDICATE =
            "EXISTS (SELECT 1 FROM app_user r WHERE r.avatar_asset_id = %1$s) "
                    + "OR EXISTS (SELECT 1 FROM chat r WHERE r.image_asset_id = %1$s) "
                    + "OR EXISTS (SELECT 1 FROM character r WHERE r.portrait_asset_id = %1$s) "
                    + "OR EXISTS (SELECT 1 FROM item r WHERE r.image_asset_id = %1$s) "
                    + "OR EXISTS (SELECT 1 FROM storage r WHERE r.image_asset_id = %1$s)";

    private static final String COLUMNS =
            "id, game_id, owner_user_id, mime, byte_size, width, height, sha256, "
                    + "original_name, storage_key, created_at";

    private final JdbcTemplate jdbc;
    private final RowMapper<Asset> mapper = this::map;

    public AssetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static String referencedBy(String assetColumn) {
        return REFERENCE_PREDICATE.formatted(assetColumn);
    }

    public void insert(Asset asset) {
        jdbc.update(
                "INSERT INTO asset (id, game_id, owner_user_id, mime, byte_size, width, height, "
                        + "sha256, original_name, storage_key, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                asset.id(),
                asset.gameId(),
                asset.ownerUserId(),
                asset.mime(),
                asset.byteSize(),
                asset.width(),
                asset.height(),
                asset.sha256(),
                asset.originalName(),
                asset.storageKey(),
                Timestamp.from(asset.createdAt()));
    }

    public Optional<Asset> findById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM asset WHERE id = ?", mapper, id)
                .stream()
                .findFirst();
    }

    /** Сколько байт игра уже заняла: без этого квота проверялась бы только по последнему файлу. */
    public long bytesByGame(UUID gameId) {
        Long total = jdbc.queryForObject(
                "SELECT coalesce(sum(byte_size), 0) FROM asset WHERE game_id = ?", Long.class, gameId);
        return total == null ? 0L : total;
    }

    public long countByGame(UUID gameId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM asset WHERE game_id = ?", Long.class, gameId);
        return count == null ? 0L : count;
    }

    public boolean isReferenced(UUID assetId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM asset a WHERE a.id = ? AND (" + referencedBy("a.id") + ")",
                Long.class,
                assetId);
        return count != null && count > 0;
    }

    /**
     * Ассеты, на которые не ссылается ни одна сущность. Архивные локации и персонажи
     * считаются ссылкой намеренно: на них ссылается транскрипт, и до конца жизни игры
     * картинка обязана остаться (`D-077`).
     *
     * @param createdBefore граница «карантина»: свежий ассет ещё может ждать привязки,
     *                      поэтому сборка его не рассматривает
     */
    public List<Asset> findUnreferenced(Instant createdBefore) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM asset a "
                        + "WHERE NOT (" + referencedBy("a.id") + ") AND a.created_at < ? "
                        + "ORDER BY created_at, id",
                mapper,
                Timestamp.from(createdBefore));
    }

    /** Удаление строки ассета. Вызывается только орфан-сборкой — см. javadoc класса. */
    public void purge(UUID id) {
        jdbc.update("DELETE FROM asset WHERE id = ?", id);
    }

    private Asset map(ResultSet rs, int rowNum) throws SQLException {
        Integer width = rs.getObject("width", Integer.class);
        Integer height = rs.getObject("height", Integer.class);
        return new Asset(
                rs.getObject("id", UUID.class),
                rs.getObject("game_id", UUID.class),
                rs.getObject("owner_user_id", UUID.class),
                rs.getString("mime"),
                rs.getLong("byte_size"),
                width == null ? 0 : width,
                height == null ? 0 : height,
                rs.getString("sha256"),
                rs.getString("original_name"),
                rs.getString("storage_key"),
                rs.getTimestamp("created_at").toInstant());
    }
}
