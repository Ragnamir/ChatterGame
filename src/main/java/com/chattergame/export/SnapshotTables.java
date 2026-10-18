package com.chattergame.export;

import java.util.List;

/**
 * Что входит в снимок игры и в каком порядке восстанавливается (`D-061`, `D-146`).
 *
 * <p>Порядок один для выгрузки и загрузки: он же порядок вставки, и в нём учтены внешние
 * ключи. Единственный цикл — `membership.reserved_character_id` ↔
 * `character.occupant_membership_id` — разрешается не порядком, а добором после вставки
 * ({@link GameSnapshotRestorer}).
 *
 * <p>Список колонок — часть контракта снимка: `app_user` выгружается без почты, хеша пароля
 * и аватара (`D-079`, `D-095`), `asset` не выгружается вовсе, потому что бинарники лежат
 * в бэкапе медиа (`D-101`). Ссылки на ассеты остаются идентификаторами: внешних ключей
 * на `asset` в схеме нет.
 */
final class SnapshotTables {

    record TableSpec(String name, List<String> columns, String select) {
    }

    private SnapshotTables() {
    }

    /**
     * Порядок вставки. `app_user` — первым: на него ссылаются участие и журнал. `note`
     * раньше `saved_message`, `message` раньше `inbox_entry`, `turn` раньше `turn_budget`.
     */
    static final List<TableSpec> ALL = List.of(
            new TableSpec(
                    "app_user",
                    List.of("id", "login", "login_normalized", "display_name", "theme", "status",
                            "created_at", "updated_at"),
                    "SELECT id, login, login_normalized, display_name, theme, status, created_at, "
                            + "updated_at FROM app_user "
                            + "WHERE id IN (SELECT user_id FROM membership WHERE game_id = ?) "
                            + "ORDER BY id"),
            new TableSpec(
                    "game",
                    List.of("id", "master_user_id", "title", "public_description",
                            "private_description", "readiness", "visibility", "phase", "paused",
                            "pause_reason", "turn_based", "archived", "archived_reason",
                            "final_chat_id", "created_at", "updated_at"),
                    "SELECT id, master_user_id, title, public_description, private_description, "
                            + "readiness, visibility, phase, paused, pause_reason, turn_based, "
                            + "archived, archived_reason, final_chat_id, created_at, updated_at "
                            + "FROM game WHERE id = ?"),
            new TableSpec(
                    "membership",
                    List.of("id", "game_id", "user_id", "role", "status", "created_at", "joined_at",
                            "left_at", "reserved_character_id", "roleplay_wishes", "backfill_pending"),
                    "SELECT id, game_id, user_id, role, status, created_at, joined_at, left_at, "
                            + "reserved_character_id, roleplay_wishes, backfill_pending "
                            + "FROM membership WHERE game_id = ? ORDER BY id"),
            new TableSpec(
                    "chat",
                    List.of("id", "game_id", "kind", "title", "description", "image_asset_id",
                            "owner_membership_id", "archived", "disabled", "created_at",
                            "archived_reason", "archived_at"),
                    "SELECT id, game_id, kind, title, description, image_asset_id, "
                            + "owner_membership_id, archived, disabled, created_at, archived_reason, "
                            + "archived_at FROM chat WHERE game_id = ? ORDER BY id"),
            new TableSpec(
                    "chat_member",
                    List.of("id", "chat_id", "subject_kind", "subject_id", "state", "invited_by",
                            "created_at", "joined_at", "left_at"),
                    "SELECT m.id, m.chat_id, m.subject_kind, m.subject_id, m.state, m.invited_by, "
                            + "m.created_at, m.joined_at, m.left_at FROM chat_member m "
                            + "JOIN chat c ON c.id = m.chat_id WHERE c.game_id = ? ORDER BY m.id"),
            new TableSpec(
                    "transition",
                    List.of("id", "game_id", "from_chat_id", "to_chat_id", "title", "archived",
                            "created_at", "archived_reason", "archived_at"),
                    "SELECT id, game_id, from_chat_id, to_chat_id, title, archived, created_at, "
                            + "archived_reason, archived_at FROM transition WHERE game_id = ? "
                            + "ORDER BY id"),
            new TableSpec(
                    "transition_state",
                    List.of("id", "transition_id", "character_id", "state", "reason"),
                    "SELECT s.id, s.transition_id, s.character_id, s.state, s.reason "
                            + "FROM transition_state s JOIN transition t ON t.id = s.transition_id "
                            + "WHERE t.game_id = ? ORDER BY s.id"),
            new TableSpec(
                    "character",
                    List.of("id", "game_id", "name", "portrait_asset_id", "appearance",
                            "public_history", "private_history", "playable", "npc",
                            "occupant_membership_id", "archived", "created_at", "updated_at",
                            "archived_reason"),
                    "SELECT id, game_id, name, portrait_asset_id, appearance, public_history, "
                            + "private_history, playable, npc, occupant_membership_id, archived, "
                            + "created_at, updated_at, archived_reason FROM character "
                            + "WHERE game_id = ? ORDER BY id"),
            new TableSpec(
                    "presence",
                    List.of("id", "character_id", "chat_id", "position", "hidden", "joined_at",
                            "left_at"),
                    "SELECT p.id, p.character_id, p.chat_id, p.position, p.hidden, p.joined_at, "
                            + "p.left_at FROM presence p JOIN character ch ON ch.id = p.character_id "
                            + "WHERE ch.game_id = ? ORDER BY p.id"),
            new TableSpec(
                    "restriction",
                    List.of("id", "game_id", "subject_kind", "subject_id", "kind", "reason",
                            "created_by", "created_at", "expires_at", "revoked_at"),
                    "SELECT id, game_id, subject_kind, subject_id, kind, reason, created_by, "
                            + "created_at, expires_at, revoked_at FROM restriction "
                            + "WHERE game_id = ? ORDER BY id"),
            new TableSpec(
                    "storage",
                    List.of("id", "game_id", "kind", "chat_id", "character_id", "title",
                            "description", "is_open", "created_at", "image_asset_id"),
                    "SELECT id, game_id, kind, chat_id, character_id, title, description, is_open, "
                            + "created_at, image_asset_id FROM storage WHERE game_id = ? "
                            + "ORDER BY id"),
            new TableSpec(
                    "item",
                    List.of("id", "game_id", "name", "description", "image_asset_id", "size",
                            "properties", "created_at", "updated_at", "type", "quantity"),
                    "SELECT id, game_id, name, description, image_asset_id, size, properties, "
                            + "created_at, updated_at, type, quantity FROM item WHERE game_id = ? "
                            + "ORDER BY id"),
            new TableSpec(
                    "item_placement",
                    List.of("id", "item_id", "storage_id", "placed_at"),
                    "SELECT p.id, p.item_id, p.storage_id, p.placed_at FROM item_placement p "
                            + "JOIN item i ON i.id = p.item_id WHERE i.game_id = ? ORDER BY p.id"),
            new TableSpec(
                    "message",
                    List.of("id", "game_id", "chat_id", "seq", "kind", "character_id",
                            "author_membership_id", "author_user_id", "on_behalf_of_master", "body",
                            "payload", "created_at", "matched_note_ids"),
                    "SELECT id, game_id, chat_id, seq, kind, character_id, author_membership_id, "
                            + "author_user_id, on_behalf_of_master, body, payload, created_at, "
                            + "matched_note_ids FROM message WHERE game_id = ? ORDER BY id"),
            new TableSpec(
                    "inbox_entry",
                    List.of("id", "subject_kind", "subject_id", "chat_id", "event_seq", "message_id",
                            "created_at"),
                    "SELECT i.id, i.subject_kind, i.subject_id, i.chat_id, i.event_seq, "
                            + "i.message_id, i.created_at FROM inbox_entry i "
                            + "JOIN chat c ON c.id = i.chat_id WHERE c.game_id = ? ORDER BY i.id"),
            new TableSpec(
                    "read_cursor",
                    List.of("subject_kind", "subject_id", "chat_id", "last_seq", "updated_at"),
                    "SELECT r.subject_kind, r.subject_id, r.chat_id, r.last_seq, r.updated_at "
                            + "FROM read_cursor r JOIN chat c ON c.id = r.chat_id "
                            + "WHERE c.game_id = ? "
                            + "ORDER BY r.subject_kind, r.subject_id, r.chat_id"),
            new TableSpec(
                    "note",
                    List.of("id", "game_id", "author_membership_id", "target_kind", "target_id",
                            "body", "matching_pending", "created_at", "updated_at"),
                    "SELECT id, game_id, author_membership_id, target_kind, target_id, body, "
                            + "matching_pending, created_at, updated_at FROM note "
                            + "WHERE game_id = ? ORDER BY id"),
            new TableSpec(
                    "note_keyword",
                    List.of("id", "note_id", "raw", "normalized", "exact_match", "superseded"),
                    "SELECT k.id, k.note_id, k.raw, k.normalized, k.exact_match, k.superseded "
                            + "FROM note_keyword k JOIN note n ON n.id = k.note_id "
                            + "WHERE n.game_id = ? ORDER BY k.id"),
            new TableSpec(
                    "saved_message",
                    List.of("id", "game_id", "owner_membership_id", "message_id", "text_snapshot",
                            "speaker_snapshot", "comment", "note_id", "created_at"),
                    "SELECT id, game_id, owner_membership_id, message_id, text_snapshot, "
                            + "speaker_snapshot, comment, note_id, created_at FROM saved_message "
                            + "WHERE game_id = ? ORDER BY id"),
            new TableSpec(
                    "turn",
                    List.of("id", "game_id", "n", "status", "opened_at", "closed_by", "closed_at"),
                    "SELECT id, game_id, n, status, opened_at, closed_by, closed_at FROM turn "
                            + "WHERE game_id = ? ORDER BY id"),
            new TableSpec(
                    "turn_budget",
                    List.of("id", "turn_id", "character_id", "action_used", "speech_used"),
                    "SELECT b.id, b.turn_id, b.character_id, b.action_used, b.speech_used "
                            + "FROM turn_budget b JOIN turn t ON t.id = b.turn_id "
                            + "WHERE t.game_id = ? ORDER BY b.id"),
            new TableSpec(
                    "game_event",
                    List.of("id", "game_id", "seq", "type", "chat_id", "actor_user_id",
                            "character_id", "on_behalf_of_master", "payload", "created_at"),
                    "SELECT id, game_id, seq, type, chat_id, actor_user_id, character_id, "
                            + "on_behalf_of_master, payload, created_at FROM game_event "
                            + "WHERE game_id = ? ORDER BY seq"),
            new TableSpec(
                    "game_stream",
                    List.of("game_id", "last_seq"),
                    "SELECT game_id, last_seq FROM game_stream WHERE game_id = ?"));

    static TableSpec byName(String name) {
        return ALL.stream()
                .filter(spec -> spec.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Таблицы нет в снимке: " + name));
    }
}
