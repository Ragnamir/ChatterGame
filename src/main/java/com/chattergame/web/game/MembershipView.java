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
 *
 * <p>`roleplayWishes` — материал мастеру, а не реплика (`D-139`), поэтому по умолчанию
 * вызов {@link #of} его не отдаёт: безопасное умолчание важнее удобства, потому что этот
 * вид переиспользуется маршрутами, где смотрящий и владелец участия — разные люди. Кто
 * вправе видеть текст, решает вызывающий: {@link #withWishes} ставится там, где это либо
 * сам автор, либо мастер.
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
        UUID reservedCharacterId,
        String roleplayWishes) {

    public static MembershipView of(Membership membership) {
        return build(membership, null);
    }

    /** Пожелания по отыгрышу видны автору и мастеру (`D-139`): это материал мастеру, не канон. */
    public static MembershipView withWishes(Membership membership) {
        return build(membership, membership.roleplayWishes());
    }

    private static MembershipView build(Membership membership, String roleplayWishes) {
        return new MembershipView(
                membership.id(),
                membership.gameId(),
                membership.userId(),
                membership.role().name(),
                membership.status().name(),
                membership.createdAt(),
                membership.joinedAt(),
                membership.leftAt(),
                membership.reservedCharacterId(),
                roleplayWishes);
    }
}
