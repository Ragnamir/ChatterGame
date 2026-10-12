package com.chattergame.character;

import java.util.UUID;

/**
 * Персонаж игры (`REQUIREMENTS §9`). Создаёт только мастер (`D-020`).
 *
 * <p>Поля карточки лежат одной строкой, а не разъехались по таблицам: открытая история
 * видна всем игрокам игры, закрытая — только играющему игроку и мастеру, внешность — всем,
 * кому виден персонаж, и отбор делает читающий слой, а не схема.
 *
 * <p>`npc` и `playable` — технические признаки (`REQUIREMENTS §9`): NPC не занимает никто,
 * но мастер пишет от его лица (`D-004`); неиграбельный персонаж — часть мира, а не слот.
 *
 * <p>`occupantMembershipId` — занятость (`E06`, `D-100`): играемого занимает один игрок
 * одновременно, NPC не занят никем. `archived` вместо удаления: после `READY` физическое
 * удаление запрещено, а «выведен из игры» показывается с причиной (`D-009`, `D-010`).
 */
public record Character(
        UUID id,
        UUID gameId,
        String name,
        UUID portraitAssetId,
        String appearance,
        String publicHistory,
        String privateHistory,
        boolean playable,
        boolean npc,
        UUID occupantMembershipId,
        boolean archived,
        String archivedReason) {

    /** Максимум колонки `character.name`; проверяется на сервисе ради текста ошибки, не только БД. */
    public static final int NAME_MAX = 200;

    /** Заведение с одним именем: карточку мастер заполняет следом, а пустая строка нужна уже графу. */
    public static Character named(UUID id, UUID gameId, String name) {
        return new Character(
                id, gameId, name, null, null, null, null, false, false, null, false, null);
    }
}
