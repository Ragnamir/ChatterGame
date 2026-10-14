package com.chattergame.web.chat;

import com.chattergame.chat.ChatMember;
import com.chattergame.chat.ChannelQueryService;
import com.chattergame.chat.Chat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Закрытый канал в ответе API (`REQUIREMENTS §7.2`–§7.5). Помимо шапки чата несёт место
 * смотрящего в канале: у шёпота нет названия, и его роль в списке исполняет состав, а
 * состояние `INVITED` — это приглашение, на которое ещё нужно ответить (`D-038`).
 *
 * <p>`unread` считается по `inbox_entry` (`D-036`), то есть тем же материалом, что и лента:
 * отдельной арифметики счётчиков нет и быть не должно.
 */
public record ChannelView(
        UUID id,
        UUID gameId,
        String kind,
        String title,
        boolean disabled,
        boolean archived,
        String archivedReason,
        Instant archivedAt,
        Instant createdAt,
        String mySubjectKind,
        String myState,
        boolean master,
        long unread,
        List<Member> members) {

    /** Участник канала: вид и идентификатор субъекта — та же пара, что в `inbox_entry` (`D-086`). */
    public record Member(String subjectKind, UUID subjectId, String state) {

        static Member of(ChatMember member) {
            return new Member(
                    member.subject().kind().name(),
                    member.subject().id(),
                    member.state().name());
        }
    }

    public static ChannelView of(ChannelQueryService.ChannelAccess access, List<ChatMember> participants) {
        Chat chat = access.chat();
        return new ChannelView(
                chat.id(),
                chat.gameId(),
                chat.kind().name(),
                chat.title(),
                chat.disabled(),
                chat.archived(),
                chat.archivedReason(),
                chat.archivedAt(),
                chat.createdAt(),
                access.member() == null ? null : access.member().subject().kind().name(),
                access.member() == null ? null : access.member().state().name(),
                access.master(),
                access.unread(),
                participants.stream().map(Member::of).toList());
    }
}
