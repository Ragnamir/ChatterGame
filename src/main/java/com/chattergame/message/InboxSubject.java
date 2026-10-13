package com.chattergame.message;

import java.util.UUID;

/**
 * Субъект получения: пара «вид + идентификатор» (`D-086`). Отдельный тип, а не два поля
 * на месте, потому что этой парой адресуются три таблицы, и перепутать вид с
 * идентификатором нельзя дать компилятору — можно только типом.
 */
public record InboxSubject(SubjectKind kind, UUID id) {

    public static InboxSubject character(UUID characterId) {
        return new InboxSubject(SubjectKind.CHARACTER, characterId);
    }

    public static InboxSubject membership(UUID membershipId) {
        return new InboxSubject(SubjectKind.MEMBERSHIP, membershipId);
    }
}
