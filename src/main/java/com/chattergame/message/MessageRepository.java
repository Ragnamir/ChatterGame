package com.chattergame.message;

import com.chattergame.journal.GameEventType;
import com.chattergame.journal.Payloads;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Доступ к `message` и чтение ленты через `inbox_entry`.
 *
 * <p>Ни один запрос отсюда не берёт `message` как самостоятельный источник строк: чтение
 * видимости идёт только через `inbox_entry` (`D-030`), и это правило проверяется на гейте
 * разбором исходника. Прямое «дай последнее сообщение чата» поэтому тоже идёт с оглядкой
 * на материал: строка, которую никто не получил, не является частью ленты.
 *
 * <p>Правки и удаления нет вовсе — отзыв сообщений невозможен (`D-008`), и отсутствие
 * метода честнее метода, который бросает исключение.
 */
@Repository
public class MessageRepository {

    private static final String COLUMNS =
            "id, game_id, chat_id, seq, kind, character_id, author_membership_id, author_user_id, "
                    + "on_behalf_of_master, body, payload, created_at";

    /** Колонки, читаемые из `message` в ленте: без них разбор идёт двумя запросами на строку. */
    private static final String FEED_COLUMNS =
            "m.id, m.game_id, m.chat_id, m.seq, m.kind, m.character_id, m.author_membership_id, "
                    + "m.author_user_id, m.on_behalf_of_master, m.body, m.payload, m.created_at";

    private final JdbcTemplate jdbc;
    private final Payloads payloads;

    public MessageRepository(JdbcTemplate jdbc, Payloads payloads) {
        this.jdbc = jdbc;
        this.payloads = payloads;
    }

    public void insert(Message message) {
        jdbc.update(
                "INSERT INTO message (id, game_id, chat_id, seq, kind, character_id, "
                        + "author_membership_id, author_user_id, on_behalf_of_master, body, payload) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)",
                message.id(),
                message.gameId(),
                message.chatId(),
                message.seq(),
                message.kind().name(),
                message.characterId(),
                message.authorMembershipId(),
                message.authorUserId(),
                message.onBehalfOfMaster(),
                message.body(),
                payloads.write(message.payload()));
    }

    /**
     * Лента субъекта в чате, продолжение от `afterSeq`. Единственный read-путь сообщений
     * (`D-030`): строка берётся из `inbox_entry`, тело подклеивается по `message_id`.
     * Порядок — по номеру события, потому что он и есть порядок игры (`D-033`).
     */
    public List<Message> feed(InboxSubject subject, UUID chatId, long afterSeq, int limit) {
        return jdbc.query(
                "SELECT " + FEED_COLUMNS + " FROM inbox_entry i "
                        + "JOIN message m ON m.id = i.message_id AND m.chat_id = i.chat_id "
                        + "WHERE i.subject_kind = ? AND i.subject_id = ? AND i.chat_id = ? "
                        + "AND i.event_seq > ? ORDER BY i.event_seq LIMIT ?",
                this::mapPrefixed,
                subject.kind().name(),
                subject.id(),
                chatId,
                afterSeq,
                limit);
    }

    /**
     * Последнее адресованное субъекту событие чата (`D-032`): при входе в локацию видно
     * только его, остальное игрок спрашивает у мастера. Строка берётся из `inbox_entry`,
     * а не из `message`: у системного события тела нет, но для входящего оно такой же
     * элемент ленты, и пропуск его сделал бы «последнее» неверным.
     */
    public Optional<FeedItem> lastFor(UUID gameId, InboxSubject subject, UUID chatId) {
        return feed(gameId, subject, chatId, 0L, Integer.MAX_VALUE)
                .stream()
                .reduce((first, second) -> second);
    }

    public long countInChat(UUID chatId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM inbox_entry WHERE chat_id = ?", Long.class, chatId);
        return count == null ? 0L : count;
    }

    /**
     * Лента чата для субъекта: сообщения и системные проекции одним порядком (`D-031`).
     *
     * <p>Источник строк — только `inbox_entry` (`D-030`); тело подклеивается из `message`,
     * а тип системного события — из журнала. Запрос без `LEFT JOIN message` и был бы
     * чтением мимо материализованной видимости, поэтому он здесь один на оба вида строк.
     */
    public List<FeedItem> feed(UUID gameId, InboxSubject subject, UUID chatId, long afterSeq, int limit) {
        return jdbc.query(
                "SELECT i.event_seq, i.chat_id, m.id AS message_id, m.kind AS message_kind, "
                        + "m.character_id, m.author_membership_id, m.author_user_id, "
                        + "m.on_behalf_of_master, m.body, m.payload AS message_payload, "
                        + "e.type AS event_type, e.payload AS event_payload, "
                        + "coalesce(m.created_at, e.created_at) AS created_at "
                        + "FROM inbox_entry i "
                        + "JOIN game_event e ON e.game_id = ? AND e.seq = i.event_seq "
                        + "LEFT JOIN message m ON m.id = i.message_id "
                        + "WHERE i.subject_kind = ? AND i.subject_id = ? AND i.chat_id = ? "
                        + "AND i.event_seq > ? ORDER BY i.event_seq LIMIT ?",
                (rs, rowNum) -> mapFeed(rs),
                gameId,
                subject.kind().name(),
                subject.id(),
                chatId,
                afterSeq,
                limit);
    }

    private FeedItem mapFeed(ResultSet rs) throws SQLException {
        UUID messageId = rs.getObject("message_id", UUID.class);
        if (messageId != null) {
            return FeedItem.of(new Message(
                    messageId,
                    null,
                    rs.getObject("chat_id", UUID.class),
                    rs.getLong("event_seq"),
                    MessageKind.valueOf(rs.getString("message_kind")),
                    rs.getObject("character_id", UUID.class),
                    rs.getObject("author_membership_id", UUID.class),
                    rs.getObject("author_user_id", UUID.class),
                    rs.getBoolean("on_behalf_of_master"),
                    rs.getString("body"),
                    payloads.read(rs.getString("message_payload")),
                    rs.getTimestamp("created_at").toInstant()));
        }
        return FeedItem.system(
                rs.getLong("event_seq"),
                rs.getObject("chat_id", UUID.class),
                SystemProjection.codeOf(GameEventType.valueOf(rs.getString("event_type"))),
                payloads.read(rs.getString("event_payload")),
                rs.getTimestamp("created_at").toInstant());
    }

    /**
     * Последний адресованный номер в чате (`D-032`). Читается из `inbox_entry`, а не из
     * `message`: у системных событий строки в `message` нет, а вход в локацию обязан
     * поставить курсор после них тоже.
     */
    public long maxEventSeqInChat(UUID chatId) {
        Long seq = jdbc.queryForObject(
                "SELECT max(event_seq) FROM inbox_entry WHERE chat_id = ?", Long.class, chatId);
        return seq == null ? 0L : seq;
    }

    private Message map(ResultSet rs, int rowNum) throws SQLException {
        return mapRow(rs, "");
    }

    private Message mapPrefixed(ResultSet rs, int rowNum) throws SQLException {
        return mapRow(rs, "m.");
    }

    private Message mapRow(ResultSet rs, String p) throws SQLException {
        return new Message(
                rs.getObject(p + "id", UUID.class),
                rs.getObject(p + "game_id", UUID.class),
                rs.getObject(p + "chat_id", UUID.class),
                rs.getLong(p + "seq"),
                MessageKind.valueOf(rs.getString(p + "kind")),
                rs.getObject(p + "character_id", UUID.class),
                rs.getObject(p + "author_membership_id", UUID.class),
                rs.getObject(p + "author_user_id", UUID.class),
                rs.getBoolean(p + "on_behalf_of_master"),
                rs.getString(p + "body"),
                payloads.read(rs.getString(p + "payload")),
                rs.getTimestamp(p + "created_at").toInstant());
    }
}
