package com.chattergame.chat;

import java.time.Instant;
import java.util.UUID;

/**
 * Ориентированный переход между локациями (`REQUIREMENTS §8`). Направление явное: симметрия
 * задаётся мастером двумя рёбрами, а не выводом «значит, и обратно можно».
 *
 * <p>Архивация ребра — уход из игры (`D-009`), а не закрытие: ребро закрывается состоянием
 * у конкретного персонажа и остаётся в мире, а архивация снимает его совсем. Поэтому у
 * архивации есть причина и момент, а у закрытия — своя причина в `transition_state`.
 */
public record Transition(
        UUID id,
        UUID gameId,
        UUID fromChatId,
        UUID toChatId,
        String title,
        boolean archived,
        String archivedReason,
        Instant archivedAt,
        Instant createdAt) {

    /** Максимум колонки `transition.title`; название необязательно, проверяется только длина. */
    public static final int TITLE_MAX = 200;
}
