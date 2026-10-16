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
        Instant leftAt,
        UUID reservedCharacterId,
        String roleplayWishes) {

    /** Предел «пожеланий по отыгрышу» (`REQUIREMENTS §17`): материал мастеру, не канон. */
    public static final int ROLEPLAY_WISHES_MAX = 500;

    /** Мастер создаёт игру и сразу в ней участвует — приглашать его некому (`REQUIREMENTS §6`). */
    public static Membership master(UUID id, UUID gameId, UUID userId, Instant now) {
        return new Membership(
                id, gameId, userId, MembershipRole.MASTER, MembershipStatus.JOINED, now, now, null, null, null);
    }

    /** Копия строки со сменой статуса: остальные поля принадлежат участию, а не переходу. */
    public Membership withStatus(MembershipStatus next, Instant now) {
        return new Membership(
                id,
                gameId,
                userId,
                role,
                next,
                createdAt,
                next == MembershipStatus.JOINED ? now : joinedAt,
                next == MembershipStatus.LEFT ? now : leftAt,
                next == MembershipStatus.INVITED ? reservedCharacterId : null,
                roleplayWishes);
    }

    /** Роль меняется ровно один раз и ровно в одну сторону: наблюдатель становится игроком (`D-130`). */
    public Membership withRole(MembershipRole next) {
        return new Membership(
                id, gameId, userId, next, status, createdAt, joinedAt, leftAt, reservedCharacterId,
                roleplayWishes);
    }

    public Membership withReservedCharacter(UUID characterId) {
        return new Membership(
                id, gameId, userId, role, status, createdAt, joinedAt, leftAt, characterId, roleplayWishes);
    }

    /** Пожелания по отыгрышу — текст самого игрока, поэтому меняются отдельным действием. */
    public Membership withRoleplayWishes(String wishes) {
        return new Membership(
                id, gameId, userId, role, status, createdAt, joinedAt, leftAt, reservedCharacterId, wishes);
    }

    public boolean joined() {
        return status == MembershipStatus.JOINED;
    }

    public boolean banned() {
        return status == MembershipStatus.BANNED;
    }

    public boolean invited() {
        return status == MembershipStatus.INVITED;
    }
}
