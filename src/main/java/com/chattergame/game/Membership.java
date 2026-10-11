package com.chattergame.game;

import java.time.Instant;
import java.util.UUID;

/**
 * Участие пользователя в игре (`REQUIREMENTS §6`). Уникально по `(game, user)`: у человека
 * одна роль в одной игре, а несколько персонажей — это отдельная сущность (`D-002`).
 */
public record Membership(
        UUID id,
        UUID gameId,
        UUID userId,
        MembershipRole role,
        MembershipStatus status,
        Instant createdAt,
        Instant joinedAt,
        Instant leftAt) {

    /** Мастер создаёт игру и сразу в ней участвует — приглашать его некому (`REQUIREMENTS §6`). */
    public static Membership master(UUID id, UUID gameId, UUID userId, Instant now) {
        return new Membership(
                id, gameId, userId, MembershipRole.MASTER, MembershipStatus.JOINED, now, now, null);
    }

    public boolean joined() {
        return status == MembershipStatus.JOINED;
    }

    public boolean banned() {
        return status == MembershipStatus.BANNED;
    }
}
