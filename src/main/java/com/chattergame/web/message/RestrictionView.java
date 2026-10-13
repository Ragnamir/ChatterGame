package com.chattergame.web.message;

import com.chattergame.restriction.Restriction;

import java.time.Instant;
import java.util.UUID;

/** Ограничение в ответе (`REQUIREMENTS §13`). */
public record RestrictionView(
        UUID id,
        UUID gameId,
        String subjectKind,
        UUID subjectId,
        String kind,
        String reason,
        UUID createdBy,
        Instant createdAt,
        Instant expiresAt,
        Instant revokedAt) {

    public static RestrictionView of(Restriction restriction) {
        return new RestrictionView(
                restriction.id(),
                restriction.gameId(),
                restriction.subject().kind().name(),
                restriction.subject().id(),
                restriction.kind().name(),
                restriction.reason(),
                restriction.createdBy(),
                restriction.createdAt(),
                restriction.expiresAt(),
                restriction.revokedAt());
    }
}
