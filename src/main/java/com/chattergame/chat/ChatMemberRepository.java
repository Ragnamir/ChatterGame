package com.chattergame.chat;

import com.chattergame.message.InboxSubject;
import com.chattergame.message.SubjectKind;
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
 * Доступ к `chat_member` (`REQUIREMENTS §7.2`–§7.5). SQL участия в чате живёт только здесь;
 * сервис берёт готовые операции (`D-034`).
 *
 * <p>Удаления нет: приглашение, согласие, отказ и выход — состояния одной строки (`D-010`).
 * Моменты ставит база, а не часы приложения: иначе две ноды разошлись бы в оценке «когда
 * участник согласился». Пишет сюда только `GameMutationService`, как и во все доменные таблицы.
 */
@Repository
public class ChatMemberRepository {

    private static final String COLUMNS =
            "id, chat_id, subject_kind, subject_id, state, invited_by, created_at, joined_at, left_at";
    private static final String COLUMNS_M =
            "m.id, m.chat_id, m.subject_kind, m.subject_id, m.state, m.invited_by, "
                    + "m.created_at, m.joined_at, m.left_at";

    private final JdbcTemplate jdbc;
    private final RowMapper<ChatMember> mapper = this::map;

    public ChatMemberRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ChatMember member) {
        jdbc.update(
                "INSERT INTO chat_member (id, chat_id, subject_kind, subject_id, state, invited_by, "
                        + "created_at, joined_at, left_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                member.id(),
                member.chatId(),
                member.subject().kind().name(),
                member.subject().id(),
                member.state().name(),
                member.invitedBy(),
                Timestamp.from(member.createdAt()),
                member.joinedAt() == null ? null : Timestamp.from(member.joinedAt()),
                member.leftAt() == null ? null : Timestamp.from(member.leftAt()));
    }

    public Optional<ChatMember> findById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM chat_member WHERE id = ?", mapper, id)
                .stream()
                .findFirst();
    }

    /** Активное участие субъекта: приглашение или присутствие, но не отказ и не выход. */
    public Optional<ChatMember> findActive(UUID chatId, InboxSubject subject) {
        return jdbc.query(
                        "SELECT " + COLUMNS + " FROM chat_member WHERE chat_id = ? AND subject_kind = ? "
                                + "AND subject_id = ? AND state IN ('INVITED', 'JOINED')",
                        mapper,
                        chatId,
                        subject.kind().name(),
                        subject.id())
                .stream()
                .findFirst();
    }

    /** Участие в чате в любом состоянии, включая закрытое: по нему решается повторный приём. */
    public Optional<ChatMember> findLatest(UUID chatId, InboxSubject subject) {
        return jdbc.query(
                        "SELECT " + COLUMNS + " FROM chat_member WHERE chat_id = ? AND subject_kind = ? "
                                + "AND subject_id = ? ORDER BY created_at DESC, id DESC LIMIT 1",
                        mapper,
                        chatId,
                        subject.kind().name(),
                        subject.id())
                .stream()
                .findFirst();
    }

    /** Участники чата, ещё не закрытые: приглашение и присутствие исчерпывают «кто в чате». */
    public List<ChatMember> findActiveByChat(UUID chatId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM chat_member WHERE chat_id = ? "
                        + "AND state IN ('INVITED', 'JOINED') ORDER BY created_at, id",
                mapper,
                chatId);
    }

    /**
     * Вошедшие участники чата: приглашение без согласия сюда не попадает. Этим списком
     * решается «кто получает ленту и кто может писать» (`D-038`): приглашённый узнаёт о
     * разговоре из списка каналов, но содержимого до согласия не видит.
     */
    public List<ChatMember> findJoinedByChat(UUID chatId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM chat_member WHERE chat_id = ? AND state = 'JOINED' "
                        + "ORDER BY created_at, id",
                mapper,
                chatId);
    }

    /** Участия субъекта по всем чатам игры: ими отвечает список чатов у клиента. */
    public List<ChatMember> findActiveBySubject(UUID gameId, InboxSubject subject) {
        return jdbc.query(
                "SELECT " + COLUMNS_M + " FROM chat_member m JOIN chat c ON c.id = m.chat_id "
                        + "WHERE c.game_id = ? AND m.subject_kind = ? AND m.subject_id = ? "
                        + "AND m.state IN ('INVITED', 'JOINED') ORDER BY m.created_at, m.id",
                mapper,
                gameId,
                subject.kind().name(),
                subject.id());
    }

    /** Играет ли это участие этим персонажем: занятость, а не владение строкой (`D-100`). */
    public boolean ownsCharacter(UUID membershipId, UUID characterId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM character WHERE id = ? AND occupant_membership_id = ?",
                Long.class,
                characterId,
                membershipId);
        return count != null && count > 0;
    }

    /** Сколько участий в чате прямо сейчас: им проверяется «не остался ли шёпот без людей». */
    public long countActive(UUID chatId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM chat_member WHERE chat_id = ? AND state IN ('INVITED', 'JOINED')",
                Long.class,
                chatId);
        return count == null ? 0L : count;
    }

    /** Согласие: приглашённый принимает, и участие становится живым. Момент ставит база. */
    public void join(UUID id) {
        jdbc.update(
                "UPDATE chat_member SET state = 'JOINED', joined_at = now() "
                        + "WHERE id = ? AND state = 'INVITED'",
                id);
    }

    /**
     * Закрытие участия: отказ от приглашения или выход. Два состояния, а не одно, потому что
     * повод разный и история обязана их различать.
     */
    public void close(UUID id, ChatMemberState state) {
        jdbc.update(
                "UPDATE chat_member SET state = ?, left_at = now() WHERE id = ?", state.name(), id);
    }

    private ChatMember map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp joinedAt = rs.getTimestamp("joined_at");
        Timestamp leftAt = rs.getTimestamp("left_at");
        return new ChatMember(
                rs.getObject("id", UUID.class),
                rs.getObject("chat_id", UUID.class),
                new InboxSubject(
                        SubjectKind.valueOf(rs.getString("subject_kind")),
                        rs.getObject("subject_id", UUID.class)),
                ChatMemberState.valueOf(rs.getString("state")),
                rs.getObject("invited_by", UUID.class),
                rs.getTimestamp("created_at").toInstant(),
                joinedAt == null ? null : joinedAt.toInstant(),
                leftAt == null ? null : leftAt.toInstant());
    }
}
