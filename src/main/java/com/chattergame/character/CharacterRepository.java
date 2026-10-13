package com.chattergame.character;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Доступ к `character`. Удаления нет: персонаж архивируется с причиной и остаётся читаемым
 * (`D-009`, `D-010`). Методы записи вызываются из {@code GameMutationService}, а не из
 * контроллеров.
 *
 * <p>Карточка читается и пишется целиком, потому что видимость её полей решается на
 * читающем слое: если бы репозиторий отдавал «безопасную» проекцию, правило видимости
 * разъехалось бы по всем запросам (`D-030`).
 */
@Repository
public class CharacterRepository {

    private static final String COLUMNS =
            "id, game_id, name, portrait_asset_id, appearance, public_history, private_history, "
                    + "playable, npc, occupant_membership_id, archived, archived_reason";

    private final JdbcTemplate jdbc;
    private final RowMapper<Character> mapper = this::map;

    public CharacterRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Character character) {
        jdbc.update(
                "INSERT INTO character (id, game_id, name, portrait_asset_id, appearance, "
                        + "public_history, private_history, playable, npc, occupant_membership_id, "
                        + "archived, archived_reason) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                character.id(),
                character.gameId(),
                character.name(),
                character.portraitAssetId(),
                character.appearance(),
                character.publicHistory(),
                character.privateHistory(),
                character.playable(),
                character.npc(),
                character.occupantMembershipId(),
                character.archived(),
                character.archivedReason());
    }

    public Optional<Character> findById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM character WHERE id = ?", mapper, id)
                .stream()
                .findFirst();
    }

    public List<Character> findByGame(UUID gameId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM character WHERE game_id = ? ORDER BY created_at, id",
                mapper,
                gameId);
    }

    /**
     * Блокировка строки персонажа на время транзакции. Нужна занятию (`D-100`): без неё два
     * игрока прочитают «свободен» одновременно и оба запишут себя владельцем. Блокировка
     * строки, а не `SELECT ... FOR UPDATE` по участию: слот — это персонаж, и сериализуется
     * ровно он.
     */
    public Optional<Character> lockById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM character WHERE id = ? FOR UPDATE", mapper, id)
                .stream()
                .findFirst();
    }

    /**
     * Персонажи, занятые данным участием (`REQUIREMENTS §9`). Нужны чтению хранилищ: следом
     * за игроком идут его персонажи, и «виден ли владелец» спрашивается про них.
     */
    public List<Character> findByOccupant(UUID membershipId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM character WHERE occupant_membership_id = ?",
                mapper,
                membershipId);
    }

    /** Обновление карточки целиком: частичная запись полей разошлась бы со снимком у читателей. */
    public void updateCard(Character character) {
        jdbc.update(
                "UPDATE character SET name = ?, portrait_asset_id = ?, appearance = ?, "
                        + "public_history = ?, private_history = ?, playable = ?, npc = ?, "
                        + "updated_at = now() WHERE id = ?",
                character.name(),
                character.portraitAssetId(),
                character.appearance(),
                character.publicHistory(),
                character.privateHistory(),
                character.playable(),
                character.npc(),
                character.id());
    }

    public void markArchived(UUID id, String reason) {
        jdbc.update(
                "UPDATE character SET archived = true, archived_reason = ?, updated_at = now() "
                        + "WHERE id = ?",
                reason,
                id);
    }

    private Character map(ResultSet rs, int rowNum) throws SQLException {
        return new Character(
                rs.getObject("id", UUID.class),
                rs.getObject("game_id", UUID.class),
                rs.getString("name"),
                rs.getObject("portrait_asset_id", UUID.class),
                rs.getString("appearance"),
                rs.getString("public_history"),
                rs.getString("private_history"),
                rs.getBoolean("playable"),
                rs.getBoolean("npc"),
                rs.getObject("occupant_membership_id", UUID.class),
                rs.getBoolean("archived"),
                rs.getString("archived_reason"));
    }
}
