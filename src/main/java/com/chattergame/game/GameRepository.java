package com.chattergame.game;

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
 * Доступ к `game`. Удаления здесь нет и не будет: игра никогда не удаляется, только
 * архивируется с причиной (`D-010`). Методы записи вызываются из
 * {@link GameMutationService}, а не из контроллеров.
 *
 * <p>Состояние пишется набором осей целиком: три оси независимы (`D-003`), но переход,
 * тронувший одну, обязан сохранить остальные, иначе промежуточное состояние вроде «пауза
 * снята, а причина осталась» утечёт в ответ API.
 */
@Repository
public class GameRepository {

    private static final String COLUMNS =
            "id, master_user_id, title, public_description, private_description, readiness, "
                    + "visibility, phase, paused, pause_reason, turn_based, archived, "
                    + "archived_reason, final_chat_id, created_at, updated_at";

    /** Те же колонки с префиксом таблицы: нужно там, где `game` соединяется с `membership`. */
    private static final String COLUMNS_G = "g." + COLUMNS.replace(", ", ", g.");

    private final JdbcTemplate jdbc;
    private final RowMapper<Game> mapper = this::map;

    public GameRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Game game) {
        jdbc.update(
                "INSERT INTO game (id, master_user_id, title, public_description, private_description, "
                        + "readiness, visibility, phase, paused, pause_reason, turn_based, archived, "
                        + "archived_reason, final_chat_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                game.id(),
                game.masterUserId(),
                game.title(),
                game.publicDescription(),
                game.privateDescription(),
                game.state().readiness().name(),
                game.state().visibility().name(),
                game.state().phase().name(),
                game.state().paused(),
                game.state().pauseReason(),
                game.turnBased(),
                game.archived(),
                game.archivedReason(),
                game.finalChatId(),
                Timestamp.from(game.createdAt()),
                Timestamp.from(game.updatedAt()));
    }

    public Optional<Game> findById(UUID id) {
        List<Game> rows = jdbc.query("SELECT " + COLUMNS + " FROM game WHERE id = ?", mapper, id);
        return rows.stream().findFirst();
    }

    /** Запись осей после перехода; `updated_at` двигает БД, часы приложения тут не нужны. */
    public void updateState(GameState state) {
        jdbc.update(
                "UPDATE game SET readiness = ?, visibility = ?, phase = ?, paused = ?, "
                        + "pause_reason = ?, updated_at = now() WHERE id = ?",
                state.readiness().name(),
                state.visibility().name(),
                state.phase().name(),
                state.paused(),
                state.pauseReason(),
                state.gameId());
    }

    public void updateContent(
            UUID id, String title, String publicDescription, String privateDescription) {
        jdbc.update(
                "UPDATE game SET title = ?, public_description = ?, private_description = ?, "
                        + "updated_at = now() WHERE id = ?",
                title,
                publicDescription,
                privateDescription,
                id);
    }

    public void updateVisibility(UUID id, Visibility visibility) {
        jdbc.update(
                "UPDATE game SET visibility = ?, updated_at = now() WHERE id = ?",
                visibility.name(),
                id);
    }

    /**
     * Флаг пошагового режима (`D-012`). Пишется отдельно от осей состояния: в отличие от
     * `updateState` он не трогает готовность, видимость и фазу, и перевод игры в пошаговый
     * режим посреди партии не является переходом состояния.
     */
    public void updateTurnBased(UUID id, boolean turnBased) {
        jdbc.update(
                "UPDATE game SET turn_based = ?, updated_at = now() WHERE id = ?", turnBased, id);
    }

    /**
     * Строка игры под исключительной блокировкой. Мутация, которой нужен «один мастер
     * за раз» и которой не за что зацепиться в доменных таблицах, сериализуется на самой
     * игре: у новой игры строк хода ещё нет, и `FOR UPDATE` по ним не запер бы ничего.
     */
    public void lockById(UUID id) {
        jdbc.queryForList("SELECT id FROM game WHERE id = ? FOR UPDATE", UUID.class, id);
    }

    /**
     * Строка игры под разделяемой блокировкой ключа. Это ровно та блокировка, которую
     * берёт внешний ключ `game_event.game_id` при вставке события. Взятая до блокировки
     * хода, она задаёт единый порядок «игра, затем ход» со всеми мутациями мастера
     * (`lockById`) и снимает взаимную блокировку с закрытием хода: иначе запись сообщения
     * держала бы ход и ждала игру, а закрытие держало бы игру и ждало ход.
     */
    public void lockByIdForShare(UUID id) {
        jdbc.queryForList("SELECT id FROM game WHERE id = ? FOR KEY SHARE", UUID.class, id);
    }

    /** Ссылка на чат финального обсуждения (`REQUIREMENTS §7.5`). Пишется один раз при завершении. */
    public void updateFinalChat(UUID id, UUID finalChatId) {
        jdbc.update(
                "UPDATE game SET final_chat_id = ?, updated_at = now() WHERE id = ?",
                finalChatId,
                id);
    }

    public void markArchived(UUID id, String reason) {
        jdbc.update(
                "UPDATE game SET archived = true, archived_reason = ?, updated_at = now() WHERE id = ?",
                reason,
                id);
    }

    /** Игры, которые ещё не в архиве; архивация идемпотентна и повторно их не трогает. */
    public List<UUID> findUnarchivedByMaster(UUID masterUserId) {
        return jdbc.queryForList(
                "SELECT id FROM game WHERE master_user_id = ? AND archived = false",
                UUID.class,
                masterUserId);
    }

    /** Игры, где пользователь числится участником, включая забаненные: бан виден в ответе. */
    public List<Game> findByMember(UUID userId) {
        return jdbc.query(
                "SELECT " + COLUMNS_G + " FROM game g "
                        + "JOIN membership m ON m.game_id = g.id "
                        + "WHERE m.user_id = ? ORDER BY g.created_at DESC",
                mapper,
                userId);
    }

    public long count() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM game", Long.class);
        return count == null ? 0L : count;
    }

    private Game map(ResultSet rs, int rowNum) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        return new Game(
                id,
                rs.getObject("master_user_id", UUID.class),
                rs.getString("title"),
                rs.getString("public_description"),
                rs.getString("private_description"),
                new GameState(
                        id,
                        Readiness.valueOf(rs.getString("readiness")),
                        Visibility.valueOf(rs.getString("visibility")),
                        Phase.valueOf(rs.getString("phase")),
                        rs.getBoolean("paused"),
                        rs.getString("pause_reason"),
                        rs.getBoolean("archived")),
                rs.getBoolean("turn_based"),
                rs.getBoolean("archived"),
                rs.getString("archived_reason"),
                rs.getObject("final_chat_id", UUID.class),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
