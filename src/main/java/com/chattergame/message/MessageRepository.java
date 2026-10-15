package com.chattergame.message;

import com.chattergame.journal.GameEventType;
import com.chattergame.journal.Payloads;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
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
                    + "on_behalf_of_master, body, payload, created_at, matched_note_ids";

    /** Колонки, читаемые из `message` в ленте: без них разбор идёт двумя запросами на строку. */
    private static final String FEED_COLUMNS =
            "m.id, m.game_id, m.chat_id, m.seq, m.kind, m.character_id, m.author_membership_id, "
                    + "m.author_user_id, m.on_behalf_of_master, m.body, m.payload, m.created_at, "
                    + "m.matched_note_ids";

    private final JdbcTemplate jdbc;
    private final Payloads payloads;

    public MessageRepository(JdbcTemplate jdbc, Payloads payloads) {
        this.jdbc = jdbc;
        this.payloads = payloads;
    }

    public void insert(Message message) {
        jdbc.update(
                "INSERT INTO message (id, game_id, chat_id, seq, kind, character_id, "
                        + "author_membership_id, author_user_id, on_behalf_of_master, body, payload, "
                        + "matched_note_ids) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)",
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
                payloads.write(message.payload()),
                payloads.writeNoteIds(message.matchedNoteIds()));
    }

    /**
     * Строки досчёта (`D-087`): сообщения субъекта вместе с уже стоящими у них подсветками.
     * Область ограничена `inbox_entry` одного субъекта, а не всеми сообщениями игры: десятки
     * тысяч строк вместо миллионов. Текущий список читается здесь же — по нему видно, надо
     * ли вообще писать: при пересчёте правки чаще всего ничего не меняется.
     */
    public List<NoteMatchRow> feedForMatching(InboxSubject subject) {
        return jdbc.query(
                "SELECT i.event_seq, m.id AS message_id, m.body AS body, "
                        + "m.matched_note_ids AS matched_note_ids "
                        + "FROM inbox_entry i JOIN message m ON m.id = i.message_id "
                        + "WHERE i.subject_kind = ? AND i.subject_id = ? "
                        + "ORDER BY i.event_seq",
                (rs, rowNum) -> new NoteMatchRow(
                        rs.getLong("event_seq"),
                        rs.getObject("message_id", UUID.class),
                        rs.getString("body"),
                        payloads.readNoteIds(rs.getString("matched_note_ids"))),
                subject.kind().name(),
                subject.id());
    }

    /**
     * Ставит или снимает подсветку одной заметки у одного сообщения. Снятие обязательно:
     * правка заметки может убрать ключ, и без снятия сообщение осталось бы подсвеченным
     * заметкой, которой это уже не касается. Запись идёт только при расхождении, иначе
     * досчёт писал бы десятки тысяч строк в стол, ничего не меняя.
     *
     * <p>Добавление идемпотентно на уровне условия `NOT ... @>`: один и тот же досчёт
     * может идти и от правки заметки, и от планового обхода, и без этой проверки две
     * встречные транзакции добавили бы один идентификатор дважды.
     */
    public void setMatchedNote(UUID messageId, UUID noteId, boolean matchedNow, boolean matchedBefore) {
        if (matchedNow == matchedBefore) {
            return;
        }
        if (matchedNow) {
            jdbc.update(
                    "UPDATE message "
                            + "SET matched_note_ids = coalesce(matched_note_ids, '[]'::jsonb) "
                            + "|| to_jsonb(?::text) "
                            + "WHERE id = ? AND NOT (coalesce(matched_note_ids, '[]'::jsonb) "
                            + "@> to_jsonb(?::text))",
                    noteId.toString(),
                    messageId,
                    noteId.toString());
            return;
        }
        jdbc.update(
                "UPDATE message SET matched_note_ids = coalesce(matched_note_ids, '[]'::jsonb) "
                        + "- ?::text WHERE id = ?",
                noteId.toString(),
                messageId);
    }

    /** Строка досчёта: сообщение, его тело и список уже стоящих подсветок. */
    public record NoteMatchRow(long eventSeq, UUID messageId, String body, List<UUID> matchedNoteIds) {
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
                        + "m.matched_note_ids AS matched_note_ids, "
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
                    rs.getTimestamp("created_at").toInstant(),
                    payloads.readNoteIds(rs.getString("matched_note_ids"))));
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

    /**
     * Resume (`D-083`): недостающее по всем подпискам одним запросом. Условие «чат и его
     * субъект» склеивается через `OR`, а не выполняется циклом по чатам: иначе телефон,
     * рвущий соединение десятки раз за вечер, платил бы N+1 запросом на каждое
     * переподключение. Строка берётся из `inbox_entry` (`D-030`), как и везде в чтении.
     */
    public List<FeedRow> feedForResume(
            UUID gameId, List<ChatSubscription> subscriptions, long afterSeq, int limit) {
        if (subscriptions.isEmpty()) {
            return List.of();
        }
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        args.add(gameId);
        args.add(afterSeq);
        for (ChatSubscription subscription : subscriptions) {
            if (!where.isEmpty()) {
                where.append(" OR ");
            }
            where.append("(i.chat_id = ? AND i.subject_kind = ? AND i.subject_id = ?)");
            args.add(subscription.chatId());
            args.add(subscription.subject().kind().name());
            args.add(subscription.subject().id());
        }
        args.add(limit);
        return jdbc.query(
                "SELECT i.event_seq, i.chat_id, m.id AS message_id, m.kind AS message_kind, "
                        + "m.character_id, m.author_membership_id, m.author_user_id, "
                        + "m.on_behalf_of_master, m.body, m.payload AS message_payload, "
                        + "m.matched_note_ids AS matched_note_ids, "
                        + "e.type AS event_type, e.payload AS event_payload, "
                        + "coalesce(m.created_at, e.created_at) AS created_at "
                        + "FROM inbox_entry i "
                        + "JOIN game_event e ON e.game_id = ? AND e.seq = i.event_seq "
                        + "LEFT JOIN message m ON m.id = i.message_id "
                        + "WHERE i.event_seq > ? AND (" + where + ") "
                        + "ORDER BY i.event_seq LIMIT ?",
                (rs, rowNum) -> new FeedRow(mapFeed(rs), rs.getString("event_type")),
                args.toArray());
    }

    /** Подписка сокета на чат: чат и субъект, чьими глазами он смотрит (`D-086`). */
    public record ChatSubscription(UUID chatId, InboxSubject subject) {
    }

    /**
     * Строка resume вместе с типом события журнала. Тип нужен мета-каналу: клиент по нему
     * отличает реплику от события мира, а `FeedItem` его не несёт — он описывает ленту,
     * где порядок общий и вид строки уже сведён к `kind`.
     */
    public record FeedRow(FeedItem item, String eventType) {
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
                rs.getTimestamp(p + "created_at").toInstant(),
                payloads.readNoteIds(rs.getString(p + "matched_note_ids")));
    }
}
