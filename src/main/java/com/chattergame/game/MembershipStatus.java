package com.chattergame.game;

/**
 * Статус участия (`REQUIREMENTS §6`). `BANNED` — запрет в конкретной игре, а не глобальный
 * бан и не вид `restriction` (§13): ограничения отражают игровую ситуацию, бан — решение
 * мастера о человеке.
 */
public enum MembershipStatus {
    INVITED,
    JOINED,
    LEFT,
    BANNED
}
