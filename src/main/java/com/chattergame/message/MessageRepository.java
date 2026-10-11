package com.chattergame.message;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Заглушка репозитория сообщений. Нужна гейту `E01`: инварианты «нет физического удаления
 * доменных строк» и «чтение не идёт мимо inbox_entry» проверяются на живом коде, а не на
 * пустом дереве.
 *
 * Здесь намеренно нет `DELETE FROM` — появление такого вызова обязано ломать гейт (`D-010`),
 * и чтение идёт через `inbox_entry`, а не напрямую по `message` (`D-030`).
 */
@Repository
public class MessageRepository {

    private final JdbcTemplate jdbcTemplate;

    public MessageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long countVisible(UUID chatId, UUID subjectId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM inbox_entry WHERE chat_id = ? AND subject_id = ?",
                Long.class, chatId, subjectId);
        return count == null ? 0L : count;
    }
}
