package com.chattergame.chat;

import java.util.List;
import java.util.UUID;

/**
 * Представление графа для клиента (`PLAN` E04, задача 6). Это проекция чтения, а не
 * хранимая сущность: узел — локация, ребро — переход вместе с состоянием, известным
 * конкретному смотрящему.
 *
 * <p>`archived` у локации отдан явным полем, а не пропуском строки: «выведена из игры»
 * после `READY` — это сообщение игроку, а не отсутствие данных (`D-009`).
 *
 * <p>Запись, а не класс с аксессорами вида `nodes()`: Jackson распознаёт компоненты
 * записи сам, а обычный класс без `getX()` сериализуется в `{}` — клиент получил бы
 * пустой граф без единой ошибки в логе.
 */
public record GameGraph(List<Node> nodes, List<Edge> edges) {

    public GameGraph {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
    }

    /**
     * Локация глазами смотрящего. `archivedReason` заполнен, когда локация архивирована:
     * клиент показывает текст причины, а не пустое место.
     */
    public record Node(
            UUID id,
            String title,
            String description,
            boolean archived,
            String archivedReason) {
    }

    /**
     * Ребро глазами смотрящего. `state` — это {@link EdgeState} конкретного персонажа, он
     * пуст в ответе мастеру: у мастера нет собственного знания, он видит рёбра как есть.
     * Рёбра в состоянии {@link EdgeState#UNKNOWN} в ответ не попадают, потому что их не
     * должно быть видно клиенту, а не «прислать и спрятать».
     */
    public record Edge(
            UUID id,
            UUID fromChatId,
            UUID toChatId,
            String title,
            boolean archived,
            String archivedReason,
            String state,
            String reason) {
    }
}
