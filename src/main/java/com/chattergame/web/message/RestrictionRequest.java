package com.chattergame.web.message;

import com.chattergame.message.SubjectKind;
import com.chattergame.restriction.RestrictionKind;

import java.time.Instant;
import java.util.UUID;

/**
 * Запрет в запросе мастера (`REQUIREMENTS §13`). Причина обязательна, срок — нет:
 * ограничение отражает игровую ситуацию, а не наказание с точным сроком (`D-064`).
 */
public record RestrictionRequest(
        SubjectKind subjectKind,
        UUID subjectId,
        RestrictionKind kind,
        String reason,
        Instant expiresAt) {
}
