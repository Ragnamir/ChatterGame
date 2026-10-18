package com.chattergame.export;

import com.chattergame.journal.GameEventType;
import com.chattergame.message.SystemProjection;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Человекочитаемая часть экспорта (`D-061`): транскрипты по чатам, мир, персонажи, предметы
 * и заметки одним Markdown-файлом.
 *
 * <p>Рендер идёт по тому же снимку, что уезжает в JSON: двух источников у экспорта нет,
 * иначе «человеческая» и «машинная» версии расходились бы на первой же правке запроса.
 *
 * <p>Закрытые части помечены явно — маркером `ЗАКРЫТО` и перечнем в шапке. Мастер выгружает
 * файл, чтобы отдать его заменителю вручную (`D-061`), и он обязан видеть, что именно
 * передаёт, не сверяясь с базой.
 */
@Component
public class GameMarkdownRenderer {

    static final String CLOSED = "ЗАКРЫТО";

    @SuppressWarnings("unchecked")
    public String render(Map<String, Object> document) {
        Map<String, List<Map<String, Object>>> t =
                (Map<String, List<Map<String, Object>>>) document.get("tables");
        Map<String, Map<String, Object>> characters = byId(t.get("character"));
        Map<String, Map<String, Object>> memberships = byId(t.get("membership"));
        Map<String, Map<String, Object>> users = byId(t.get("app_user"));
        Map<String, Map<String, Object>> chats = byId(t.get("chat"));
        Map<String, Map<String, Object>> storages = byId(t.get("storage"));

        Map<String, Object> game = t.get("game").get(0);
        StringBuilder out = new StringBuilder(16_384);
        header(out, game, t, memberships, users);
        world(out, game);
        graph(out, t, chats, characters);
        channels(out, t, game, chats, characters, memberships, users, storages);
        cast(out, t, characters, memberships, users, chats);
        belongings(out, t, storages, characters, chats);
        notes(out, t, characters, memberships, users);
        saved(out, t, users, memberships);
        return out.toString();
    }

    private void header(
            StringBuilder out,
            Map<String, Object> game,
            Map<String, List<Map<String, Object>>> t,
            Map<String, Map<String, Object>> memberships,
            Map<String, Map<String, Object>> users) {
        Map<String, Object> masterMembership = memberships.get(text(game, "master_user_id"));
        String master = masterMembership == null ? text(game, "master_user_id") : loginOf(masterMembership, users);

        out.append("# ").append(text(game, "title")).append('\n').append('\n');
        out.append("Снимок игры ChatterGame. Мастер: ").append(master).append(".\n\n");
        out.append("- идентификатор игры: `").append(text(game, "id")).append("`\n");
        out.append("- состояние: ").append(text(game, "readiness")).append(" / ")
                .append(text(game, "phase")).append(", видимость: ").append(text(game, "visibility"))
                .append('\n');
        out.append("- пошаговый режим: ").append(bool(game, "turn_based") ? "да" : "нет").append('\n');
        out.append("- создана: ").append(text(game, "created_at")).append('\n');
        if (bool(game, "archived")) {
            out.append("- архивирована: ").append(text(game, "archived_reason")).append('\n');
        }
        out.append('\n');

        out.append("В файле есть закрытые материалы: личные каналы, закрытые истории персонажей\n")
                .append("и заметки. Они помечены маркером «").append(CLOSED)
                .append("» и перечислены ниже — файл не предназначен для игроков.\n\n");

        List<String> closedChats = new ArrayList<>();
        for (Map<String, Object> chat : t.get("chat")) {
            if (!"LOCATION".equals(text(chat, "kind")) && !"FINAL".equals(text(chat, "kind"))) {
                closedChats.add(text(chat, "kind") + " «" + text(chat, "title") + "»");
            }
        }
        if (!closedChats.isEmpty()) {
            out.append("- закрытые каналы: ").append(String.join(", ", closedChats)).append('\n');
        }
        long closedHistories = t.get("character").stream()
                .filter(row -> row.get("private_history") != null)
                .count();
        if (closedHistories > 0) {
            out.append("- закрытые истории персонажей: ").append(closedHistories).append('\n');
        }
        if (!t.get("note").isEmpty()) {
            out.append("- заметки: ").append(t.get("note").size()).append('\n');
        }
        out.append('\n');
    }

    private void world(StringBuilder out, Map<String, Object> game) {
        out.append("## Мир\n\n");
        section(out, "Открытое описание", text(game, "public_description"));
        section(out, CLOSED + ": описание для мастера", text(game, "private_description"));
    }

    private void graph(
            StringBuilder out,
            Map<String, List<Map<String, Object>>> t,
            Map<String, Map<String, Object>> chats,
            Map<String, Map<String, Object>> characters) {
        out.append("## Граф переходов\n\n");
        if (t.get("transition").isEmpty()) {
            out.append("Переходов нет.\n\n");
            return;
        }
        Map<String, List<Map<String, Object>>> states = groupBy(t.get("transition_state"), "transition_id");
        for (Map<String, Object> transition : t.get("transition")) {
            String from = titleOf(chats, text(transition, "from_chat_id"));
            String to = titleOf(chats, text(transition, "to_chat_id"));
            out.append("- ").append(from).append(" → ").append(to);
            if (transition.get("title") != null) {
                out.append(" («").append(text(transition, "title")).append("»)");
            }
            if (bool(transition, "archived")) {
                out.append(" — архивирован: ").append(text(transition, "archived_reason"));
            }
            out.append('\n');
            for (Map<String, Object> state : states.getOrDefault(text(transition, "id"), List.of())) {
                String who = nameOf(characters, text(state, "character_id"));
                out.append("    - ").append(who).append(": ").append(text(state, "state"));
                if (state.get("reason") != null) {
                    out.append(" (").append(text(state, "reason")).append(')');
                }
                out.append('\n');
            }
        }
        out.append('\n');
    }

    private void channels(
            StringBuilder out,
            Map<String, List<Map<String, Object>>> t,
            Map<String, Object> game,
            Map<String, Map<String, Object>> chats,
            Map<String, Map<String, Object>> characters,
            Map<String, Map<String, Object>> memberships,
            Map<String, Map<String, Object>> users,
            Map<String, Map<String, Object>> storages) {
        out.append("## Чаты и транскрипты\n\n");

        Map<String, List<Map<String, Object>>> messages = groupBy(t.get("message"), "chat_id");
        Map<String, List<Map<String, Object>>> system = systemEventsByChat(t.get("game_event"));
        Map<String, List<Map<String, Object>>> presence = groupBy(t.get("presence"), "chat_id");
        Map<String, List<Map<String, Object>>> members = groupBy(t.get("chat_member"), "chat_id");
        Map<String, List<Map<String, Object>>> chatStorages = groupBy(t.get("storage"), "chat_id");

        for (Map<String, Object> chat : t.get("chat")) {
            String chatId = text(chat, "id");
            String kind = text(chat, "kind");
            out.append("### ").append(text(chat, "title")).append(" — ").append(kind);
            if (!"LOCATION".equals(kind) && !"FINAL".equals(kind)) {
                out.append(" [").append(CLOSED).append(": канал только для участников]");
            }
            out.append("\n\n");
            if (chat.get("description") != null) {
                out.append(text(chat, "description")).append("\n\n");
            }
            if (bool(chat, "archived")) {
                out.append("Чат архивирован: ").append(text(chat, "archived_reason")).append("\n\n");
            }
            if (bool(chat, "disabled")) {
                out.append("Чат отключён мастером.\n\n");
            }

            List<String> inside = new ArrayList<>();
            for (Map<String, Object> row : presence.getOrDefault(chatId, List.of())) {
                if (row.get("left_at") == null) {
                    String who = nameOf(characters, text(row, "character_id"));
                    inside.add(bool(row, "hidden") ? who + " (скрыт)" : who);
                }
            }
            if (!inside.isEmpty()) {
                out.append("Присутствуют: ").append(String.join(", ", inside)).append("\n\n");
            }
            List<String> memberList = new ArrayList<>();
            for (Map<String, Object> row : members.getOrDefault(chatId, List.of())) {
                String who = "CHARACTER".equals(text(row, "subject_kind"))
                        ? nameOf(characters, text(row, "subject_id"))
                        : loginOf(memberships.get(text(row, "subject_id")), users);
                memberList.add(who + " (" + text(row, "state") + ")");
            }
            if (!memberList.isEmpty()) {
                out.append("Участники: ").append(String.join(", ", memberList)).append("\n\n");
            }
            for (Map<String, Object> storage : chatStorages.getOrDefault(chatId, List.of())) {
                out.append("Хранилище «").append(text(storage, "title")).append("» (")
                        .append(text(storage, "kind")).append(", ")
                        .append(bool(storage, "is_open") ? "открыто" : "закрыто").append(")\n\n");
            }

            List<Map<String, Object>> lines = new ArrayList<>();
            lines.addAll(messages.getOrDefault(chatId, List.of()));
            lines.addAll(system.getOrDefault(chatId, List.of()));
            lines.sort(Comparator.comparingLong(row -> number(row, "seq")));

            out.append("#### Транскрипт\n\n");
            if (lines.isEmpty()) {
                out.append("Сообщений нет.\n\n");
                continue;
            }
            for (Map<String, Object> row : lines) {
                renderLine(out, row, characters, memberships, users);
            }
            out.append('\n');
        }
    }

    private void renderLine(
            StringBuilder out,
            Map<String, Object> row,
            Map<String, Map<String, Object>> characters,
            Map<String, Map<String, Object>> memberships,
            Map<String, Map<String, Object>> users) {
        if (row.get("type") != null) {
            out.append("`#").append(number(row, "seq")).append("` *событие: ")
                    .append(text(row, "type")).append("*\n\n");
            return;
        }
        String kind = text(row, "kind");
        String who = row.get("character_id") != null
                ? nameOf(characters, text(row, "character_id"))
                : loginOf(memberships.get(text(row, "author_membership_id")), users);
        boolean hidden = false;
        if (row.get("payload") instanceof Map<?, ?> payload) {
            hidden = Boolean.TRUE.equals(payload.get("hiddenAuthor"));
        }
        String body = row.get("body") == null ? "" : text(row, "body");

        out.append("`#").append(number(row, "seq")).append("` ");
        switch (kind) {
            case "SPEECH" -> out.append("**").append(who).append(":** «").append(body).append('»');
            case "ACTION" -> out.append("*").append(who).append(": ").append(body).append('*');
            case "JOKE" -> out.append("**").append(who).append(" (шутка):** ").append(body);
            case "MASTER" -> out.append("**Мастер:** ").append(body);
            default -> out.append("**").append(who).append(":** ").append(body);
        }
        if (hidden) {
            out.append(" [скрыт]");
        }
        out.append('\n');
    }

    private void cast(
            StringBuilder out,
            Map<String, List<Map<String, Object>>> t,
            Map<String, Map<String, Object>> characters,
            Map<String, Map<String, Object>> memberships,
            Map<String, Map<String, Object>> users,
            Map<String, Map<String, Object>> chats) {
        out.append("## Персонажи\n\n");
        if (t.get("character").isEmpty()) {
            out.append("Персонажей нет.\n\n");
            return;
        }
        Map<String, List<Map<String, Object>>> presence = groupBy(t.get("presence"), "character_id");
        for (Map<String, Object> character : t.get("character")) {
            out.append("### ").append(text(character, "name")).append("\n\n");
            List<String> flags = new ArrayList<>();
            flags.add(bool(character, "playable") ? "играбельный" : "неиграбельный");
            if (bool(character, "npc")) {
                flags.add("NPC");
            }
            if (bool(character, "archived")) {
                flags.add("архивирован: " + text(character, "archived_reason"));
            }
            out.append(String.join(", ", flags)).append("\n\n");

            String occupant = text(character, "occupant_membership_id");
            if (occupant != null && memberships.get(occupant) != null) {
                out.append("Играет: ").append(loginOf(memberships.get(occupant), users)).append("\n\n");
            }
            for (Map<String, Object> row : presence.getOrDefault(text(character, "id"), List.of())) {
                if (row.get("left_at") == null) {
                    out.append("Находится: ").append(titleOf(chats, text(row, "chat_id")));
                    if (row.get("position") != null) {
                        out.append(", ").append(text(row, "position"));
                    }
                    if (bool(row, "hidden")) {
                        out.append(" (скрыт)");
                    }
                    out.append("\n\n");
                }
            }
            section(out, "Внешность", text(character, "appearance"));
            section(out, "Открытая история", text(character, "public_history"));
            section(out, CLOSED + ": закрытая история", text(character, "private_history"));
            if (character.get("portrait_asset_id") != null) {
                out.append("Портрет: `").append(text(character, "portrait_asset_id")).append("`\n\n");
            }
        }
    }

    private void belongings(
            StringBuilder out,
            Map<String, List<Map<String, Object>>> t,
            Map<String, Map<String, Object>> storages,
            Map<String, Map<String, Object>> characters,
            Map<String, Map<String, Object>> chats) {
        out.append("## Предметы и хранилища\n\n");
        if (t.get("storage").isEmpty()) {
            out.append("Хранилищ нет.\n\n");
            return;
        }
        Map<String, List<Map<String, Object>>> placements = groupBy(t.get("item_placement"), "storage_id");
        Map<String, Map<String, Object>> items = byId(t.get("item"));
        for (Map<String, Object> storage : t.get("storage")) {
            out.append("### ").append(text(storage, "title")).append(" — ")
                    .append(text(storage, "kind")).append("\n\n");
            String owner = text(storage, "character_id") != null
                    ? "персонаж " + nameOf(characters, text(storage, "character_id"))
                    : "локация " + titleOf(chats, text(storage, "chat_id"));
            out.append(owner).append(", ").append(bool(storage, "is_open") ? "открыто" : "закрыто")
                    .append('\n');
            if (storage.get("description") != null) {
                out.append('\n').append(text(storage, "description")).append('\n');
            }
            out.append('\n');
            List<Map<String, Object>> placed = placements.getOrDefault(text(storage, "id"), List.of());
            if (placed.isEmpty()) {
                out.append("Пусто.\n\n");
                continue;
            }
            for (Map<String, Object> placement : placed) {
                Map<String, Object> item = items.get(text(placement, "item_id"));
                if (item == null) {
                    continue;
                }
                out.append("- ").append(text(item, "name")).append(" (")
                        .append(text(item, "type")).append(", ").append(text(item, "size"))
                        .append(", ").append(number(item, "quantity")).append(" шт.)");
                if (item.get("description") != null) {
                    out.append(": ").append(text(item, "description"));
                }
                out.append('\n');
            }
            out.append('\n');
        }
    }

    private void notes(
            StringBuilder out,
            Map<String, List<Map<String, Object>>> t,
            Map<String, Map<String, Object>> characters,
            Map<String, Map<String, Object>> memberships,
            Map<String, Map<String, Object>> users) {
        out.append("## Заметки\n\n");
        if (t.get("note").isEmpty()) {
            out.append("Заметок нет.\n\n");
            return;
        }
        out.append(CLOSED).append(": заметка принадлежит автору и её цели, мастер видит заметки\n")
                .append("игроков (`REQUIREMENTS §15.1`).\n\n");
        Map<String, List<Map<String, Object>>> keywords = groupBy(t.get("note_keyword"), "note_id");
        for (Map<String, Object> note : t.get("note")) {
            String target = "CHARACTER".equals(text(note, "target_kind"))
                    ? nameOf(characters, text(note, "target_id"))
                    : "мастер";
            out.append("### Заметка ").append(target).append('\n').append('\n');
            out.append(text(note, "body")).append("\n\n");
            List<String> words = new ArrayList<>();
            for (Map<String, Object> keyword : keywords.getOrDefault(text(note, "id"), List.of())) {
                if (!bool(keyword, "superseded")) {
                    words.add(text(keyword, "raw"));
                }
            }
            if (!words.isEmpty()) {
                out.append("Ключи: ").append(String.join(", ", words)).append('\n');
            }
            out.append("Автор: ")
                    .append(loginOf(memberships.get(text(note, "author_membership_id")), users))
                    .append('\n').append('\n');
        }
    }

    private void saved(
            StringBuilder out,
            Map<String, List<Map<String, Object>>> t,
            Map<String, Map<String, Object>> users,
            Map<String, Map<String, Object>> memberships) {
        out.append("## Сохранённые сообщения\n\n");
        if (t.get("saved_message").isEmpty()) {
            out.append("Сохранённых сообщений нет.\n\n");
            return;
        }
        for (Map<String, Object> saved : t.get("saved_message")) {
            out.append("- **").append(text(saved, "speaker_snapshot")).append(":** ")
                    .append(text(saved, "text_snapshot"));
            if (saved.get("comment") != null) {
                out.append(" — комментарий: ").append(text(saved, "comment"));
            }
            out.append(" (сохранил ")
                    .append(loginOf(memberships.get(text(saved, "owner_membership_id")), users))
                    .append(")\n");
        }
        out.append('\n');
    }

    private static Map<String, List<Map<String, Object>>> systemEventsByChat(List<Map<String, Object>> events) {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (Map<String, Object> event : events) {
            GameEventType type;
            try {
                type = GameEventType.valueOf(text(event, "type"));
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (!SystemProjection.projects(type)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = event.get("payload") instanceof Map<?, ?> map
                    ? (Map<String, Object>) map
                    : Map.of();
            SystemProjection.chatIdOf(payload).ifPresent(chatId -> result
                    .computeIfAbsent(chatId.toString(), key -> new ArrayList<>())
                    .add(event));
        }
        return result;
    }

    private static void section(StringBuilder out, String title, String body) {
        if (body == null || body.isBlank()) {
            return;
        }
        out.append("### ").append(title).append('\n').append('\n').append(body).append("\n\n");
    }

    private static Map<String, Map<String, Object>> byId(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            result.put(text(row, "id"), row);
        }
        return result;
    }

    private static Map<String, List<Map<String, Object>>> groupBy(List<Map<String, Object>> rows, String column) {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object key = row.get(column);
            if (key != null) {
                result.computeIfAbsent(key.toString(), ignored -> new ArrayList<>()).add(row);
            }
        }
        return result;
    }

    private static String loginOf(Map<String, Object> membership, Map<String, Map<String, Object>> users) {
        if (membership == null) {
            return "неизвестный участник";
        }
        Map<String, Object> user = users.get(text(membership, "user_id"));
        if (user == null) {
            return text(membership, "user_id");
        }
        String display = user.get("display_name") == null ? "" : text(user, "display_name");
        String login = text(user, "login");
        return display.isBlank() || Objects.equals(display, login) ? login : display + " (" + login + ")";
    }

    private static String nameOf(Map<String, Map<String, Object>> characters, String id) {
        Map<String, Object> character = characters.get(id);
        return character == null ? String.valueOf(id) : text(character, "name");
    }

    private static String titleOf(Map<String, Map<String, Object>> chats, String id) {
        Map<String, Object> chat = chats.get(id);
        return chat == null ? String.valueOf(id) : text(chat, "title");
    }

    private static String text(Map<String, Object> row, String column) {
        Object value = row.get(column);
        return value == null ? null : value.toString();
    }

    private static boolean bool(Map<String, Object> row, String column) {
        return Boolean.TRUE.equals(row.get(column));
    }

    private static long number(Map<String, Object> row, String column) {
        Object value = row.get(column);
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
