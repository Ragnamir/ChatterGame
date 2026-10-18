package com.chattergame.llm;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Тексты игры для контекста модели (`REQUIREMENTS §18`, `D-045`, `D-046`).
 *
 * <p>Читаются только открытые каналы — локации и финальное обсуждение. Личные диалоги
 * игроков с мастером и шёпот не отдаются провайдеру вовсе (`D-046`): это не «данные игры»,
 * а частная переписка, и её попадание в чужой сервис не отменяется тем, что мастер её видит.
 *
 * <p>Строка сообщения берётся через `inbox_entry`, а не напрямую из `message`: у сообщения
 * нет собственного признака «кому видно», видимость материализована (`D-030`). Проверка
 * `EXISTS` здесь означает «сообщение кому-то доставлено», а не «его видит каждый».
 */
@Repository
public class LlmContextRepository {

    private static final String SELECT =
            "SELECT c.title AS chat_title, m.kind, m.body, ch.name AS speaker, m.created_at "
                    + "FROM message m "
                    + "JOIN chat c ON c.id = m.chat_id "
                    + "LEFT JOIN character ch ON ch.id = m.character_id "
                    + "WHERE m.game_id = ? AND m.body IS NOT NULL "
                    + "AND c.kind IN ('LOCATION', 'FINAL') "
                    + "AND EXISTS (SELECT 1 FROM inbox_entry i "
                    + "            WHERE i.message_id = m.id AND i.chat_id = m.chat_id) ";

    private final JdbcTemplate jdbc;

    public LlmContextRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Последние сообщения открытых каналов игры, в порядке игры. */
    public List<Line> recent(UUID gameId, int limit) {
        return query(SELECT + "ORDER BY m.created_at DESC LIMIT ?", gameId, limit);
    }

    /** Последние сообщения одного чата: автопилоту нужен только он (`D-046`). */
    public List<Line> recentInChat(UUID gameId, UUID chatId, int limit) {
        return query(
                SELECT + "AND m.chat_id = ? ORDER BY m.created_at DESC LIMIT ?",
                gameId,
                chatId,
                limit);
    }

    private List<Line> query(String sql, Object... args) {
        List<Line> lines = new ArrayList<>(jdbc.query(
                sql,
                (rs, rowNum) -> new Line(
                        rs.getString("chat_title"),
                        rs.getString("kind"),
                        rs.getString("speaker"),
                        rs.getString("body"),
                        rs.getTimestamp("created_at").toInstant()),
                args));
        Collections.reverse(lines);
        return lines;
    }

    /** Строка транскрипта: где, кто, что и когда. */
    public record Line(String chatTitle, String kind, String speaker, String body, Instant createdAt) {
    }
}
