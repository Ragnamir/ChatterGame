package com.chattergame.character;

import java.util.UUID;

/**
 * Персонаж глазами смотрящего в конкретной локации (`REQUIREMENTS §9`). Отдельная проекция,
 * а не {@link Character}: постороннему не отдаются закрытая история и служебные признаки,
 * и если бы граф отдавал сущность целиком, правило видимости пришлось бы повторять в каждом
 * потребителе (`D-030`).
 *
 * <p>`hidden` заполнен только для мастера: игроку скрытый персонаж не приходит вообще,
 * а не приходит «помеченным».
 */
public record PresentCharacter(
        UUID id,
        String name,
        UUID portraitAssetId,
        boolean hidden) {
}
