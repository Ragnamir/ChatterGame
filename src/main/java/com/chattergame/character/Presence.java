package com.chattergame.character;

import java.time.Instant;
import java.util.UUID;

/**
 * Присутствие персонажа в локации (`REQUIREMENTS §7.1`). Активная строка — та, у которой
 * `leftAt` пуст; уход её не удаляет, а закрывает (`D-007`): прошлое остаётся в журнале и в
 * истории присутствия, потому что «что случилось, то случилось» (`D-008`).
 *
 * <p>`hidden` живёт на присутствии, а не на персонаже: скрытность — это то, как персонажа
 * видят *в этой локации* (`REQUIREMENTS §7.1`), и покинув её, он ничего не уносит с собой.
 *
 * <p>Колонка `position` из baseline здесь не отражена: в документах её смысл не описан,
 * придумывать его запрещено, и колонка остаётся пустой до своего решения.
 */
public record Presence(
        UUID id,
        UUID characterId,
        UUID chatId,
        boolean hidden,
        Instant joinedAt,
        Instant leftAt) {

    /** Активное присутствие: персонаж стоит в локации прямо сейчас. */
    public boolean active() {
        return leftAt == null;
    }
}
