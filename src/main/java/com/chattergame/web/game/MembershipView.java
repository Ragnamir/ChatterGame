package com.chattergame.web.game;

import com.chattergame.game.Membership;

import java.time.Instant;
import java.util.UUID;

/**
 * Участие в ответе API (`REQUIREMENTS §6`). Статус и роль отдаются строками: клиент
 * переводит их по ключам интернализации, а не сравнивает с числом (`D-081`).
 *
 * <p>`reservedCharacterId` присутствует потому, что приглашение бронирует слот, а не
 * занимает его (`D-130`): мастеру нужно видеть, кому обещан персонаж, не дожидаясь согласия.
 */
public record MembershipView(
        UUID id,
        UUID gameId,
        UUID userId,
        String role,
        String status,
        Instant createdAt,
        Instant joinedAt,
        Instant leftAt,
        UUID reservedCharacterId) {

    public static MembershipView of(Membership membership) {
        return new MembershipView(
                membership.id(),
                membership.gameId(),
                membership.userId(),
                membership.role().name(),
                membership.status().name(),
                membership.createdAt(),
                membership.joinedAt(),
                membership.leftAt(),
                membership.reservedCharacterId());
    }
}
