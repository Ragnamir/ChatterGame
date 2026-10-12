package com.chattergame.character;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Доступ к `character`. На `E04` открыты только вставка и чтение по идентификатору: знание
 * перехода привязано к персонажу (`REQUIREMENTS §8`), и без строки персонажа тест на двух
 * персонажей с разными знаниями не поставить (`D-103`).
 *
 * <p>Полный CRUD, занятость и скрытность появятся на `E06`; здесь их нет, чтобы этап не
 * завёл вторую реализацию тех же правил.
 */
@Repository
public class CharacterRepository {

    private static final String COLUMNS = "id, game_id, name, occupant_membership_id";

    private final JdbcTemplate jdbc;

    public CharacterRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Character character) {
        jdbc.update(
                "INSERT INTO character (id, game_id, name) VALUES (?, ?, ?)",
                character.id(),
                character.gameId(),
                character.name());
    }

    public Optional<Character> findById(UUID id) {
        return jdbc.query(
                        "SELECT " + COLUMNS + " FROM character WHERE id = ?",
                        (rs, rowNum) -> new Character(
                                rs.getObject("id", UUID.class),
                                rs.getObject("game_id", UUID.class),
                                rs.getString("name"),
                                rs.getObject("occupant_membership_id", UUID.class)),
                        id)
                .stream()
                .findFirst();
    }

    public List<Character> findByGame(UUID gameId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM character WHERE game_id = ? ORDER BY created_at, id",
                (rs, rowNum) -> new Character(
                        rs.getObject("id", UUID.class),
                        rs.getObject("game_id", UUID.class),
                        rs.getString("name"),
                        rs.getObject("occupant_membership_id", UUID.class)),
                gameId);
    }
}
