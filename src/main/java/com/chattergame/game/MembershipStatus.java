package com.chattergame.game;

/**
 * Статус участия (`REQUIREMENTS §6`). `BANNED` — запрет в конкретной игре, а не глобальный
 * бан и не вид `restriction` (§13): ограничения отражают игровую ситуацию, бан — решение
 * мастера о человеке.
 *
 * <p>`DECLINED` и `LEFT` различаются затем, чтобы отказ от приглашения не читался как выход
 * из игры (`PLAN` E13, `D-123`): приглашённый не входил, и «вышел» про него — неправда.
 */
public enum MembershipStatus {
    INVITED,
    JOINED,
    DECLINED,
    LEFT,
    BANNED
}
