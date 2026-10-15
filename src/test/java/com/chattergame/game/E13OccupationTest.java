package com.chattergame.game;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Занятие и освобождение слота, свободный вход (`PLAN` E13, задача 2; `D-132`, `D-133`).
 *
 * <p>Проверяются три вещи, которые не видны из одного места: слот достаётся ровно одному
 * игроку, «один персонаж на игрока» не ограничивает, а роль при свободном входе выбирается
 * по наличию свободного слота. Последнее — граница между игроком и наблюдателем, и без него
 * наблюдатель и игрок различались бы только словом в базе.
 */
class E13OccupationTest extends E13TestSupport {

    @Test
    void freeJoinAfterReadyGivesThePlayerASlot() throws Exception {
        Fixture fixture = fixture();
        Account player = account("Входящий");
        fixture.makePublic();
        fixture.ready();

        MvcResult joined = call(
                post("/games/" + fixture.gameId + "/memberships/join"), player.token, Map.of());

        assertThat(joined.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json.readTree(bodyOf(joined));
        assertThat(body.path("role").asText()).isEqualTo("PLAYER");
        assertThat(body.path("status").asText()).isEqualTo("JOINED");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM character WHERE game_id = ? AND occupant_membership_id = ?",
                        Long.class, fixture.gameId, fixture.membershipIdOf(player.userId)))
                .as("вход игроком сразу занимает свободный слот (D-132)")
                .isEqualTo(1);
    }

    @Test
    void freeJoinWithoutASlotMakesAnObserver() throws Exception {
        Fixture fixture = fixture();
        Account first = account("ПервыйСлот");
        Account second = account("ВторойСлот");
        Account third = account("ТретийЛишний");
        fixture.makePublic();
        fixture.ready();
        call(post("/games/" + fixture.gameId + "/memberships/join"), first.token, Map.of());
        call(post("/games/" + fixture.gameId + "/memberships/join"), second.token, Map.of());

        MvcResult joined = call(
                post("/games/" + fixture.gameId + "/memberships/join"), third.token, Map.of());

        assertThat(json.readTree(bodyOf(joined)).path("role").asText())
                .as("свободных слотов нет — вход наблюдателем (D-132)")
                .isEqualTo("OBSERVER");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM character WHERE game_id = ? AND occupant_membership_id = ?",
                        Long.class, fixture.gameId, fixture.membershipIdOf(third.userId)))
                .isZero();
    }

    @Test
    void repeatJoinIsIdempotent() throws Exception {
        Fixture fixture = fixture();
        Account player = account("ВходящийДважды");
        fixture.makePublic();
        fixture.ready();
        call(post("/games/" + fixture.gameId + "/memberships/join"), player.token, Map.of());

        call(post("/games/" + fixture.gameId + "/memberships/join"), player.token, Map.of());

        assertThat(countRows(
                        "SELECT count(*) FROM membership WHERE game_id = ? AND user_id = ?",
                        fixture.gameId, player.userId))
                .as("повторный вход не заводит вторую строку участия")
                .isEqualTo(1);
    }

    @Test
    void aPrivateGameRefusesFreeJoin() throws Exception {
        Fixture fixture = fixture();
        Account player = account("Мимоходом");
        fixture.ready();

        MvcResult refused = call(
                post("/games/" + fixture.gameId + "/memberships/join"), player.token, Map.of());

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(json.readTree(bodyOf(refused)).path("code").asText()).isEqualTo("private_game");
    }

    @Test
    void freeJoinBeforeReadyIsRefused() throws Exception {
        Fixture fixture = fixture();
        Account player = account("Ранний");
        fixture.makePublic();

        MvcResult refused = call(
                post("/games/" + fixture.gameId + "/memberships/join"), player.token, Map.of());

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(bodyOf(refused)).path("code").asText()).isEqualTo("game_not_ready");
    }

    @Test
    void aPlayerMayHoldTwoCharacters() throws Exception {
        Fixture fixture = fixture();
        Account player = account("ДвумяРолями");
        fixture.makePublic();
        fixture.ready();
        UUID membershipId = joinedMembership(fixture, player);
        fixture.start();

        MvcResult first = occupy(fixture, player, fixture.firstCharacter);
        MvcResult second = occupy(fixture, player, fixture.secondCharacter);

        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(second.getResponse().getStatus())
                .as("ограничения «один персонаж на игрока» нет (PLAN E13, задача 2)")
                .isEqualTo(200);
        assertThat(countRows(
                        "SELECT count(*) FROM character WHERE game_id = ? AND occupant_membership_id = ?",
                        fixture.gameId, membershipId))
                .isEqualTo(2);
    }

    @Test
    void aTakenSlotIsRefusedToAnotherPlayer() throws Exception {
        Fixture fixture = fixture();
        Account first = account("Занявший");
        Account second = account("ОпоздавшийКСлоту");
        fixture.makePublic();
        fixture.ready();
        joinedMembership(fixture, first);
        joinedMembership(fixture, second);
        fixture.start();
        occupy(fixture, first, fixture.firstCharacter);

        MvcResult refused = occupy(fixture, second, fixture.firstCharacter);

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(bodyOf(refused)).path("code").asText())
                .isEqualTo("character_occupied");
    }

    @Test
    void releaseFreesTheSlotAndClosesPresence() throws Exception {
        Fixture fixture = fixture();
        Account player = account("Освобождающий");
        fixture.makePublic();
        fixture.ready();
        joinedMembership(fixture, player);
        fixture.start();
        occupy(fixture, player, fixture.firstCharacter);
        UUID membershipId = fixture.membershipIdOf(player.userId);

        MvcResult released = call(
                delete("/games/" + fixture.gameId + "/characters/" + fixture.firstCharacter + "/occupation"),
                player.token, null);

        assertThat(released.getResponse().getStatus()).isEqualTo(200);
        assertThat(fixture.isFree(fixture.firstCharacter)).isTrue();
        assertThat(countRows(
                        "SELECT count(*) FROM presence WHERE character_id = ? AND left_at IS NULL",
                        fixture.firstCharacter))
                .as("освобождённый персонаж не остаётся стоять в комнате")
                .isZero();
        assertThat(countRows(
                        "SELECT count(*) FROM character WHERE id = ? AND occupant_membership_id = ?",
                        fixture.firstCharacter, membershipId))
                .isZero();
    }

    @Test
    void anObserverCannotClaimACharacter() throws Exception {
        Fixture fixture = fixture();
        Account watcher = account("НаблюдательЗанятия");
        fixture.makePublic();
        fixture.ready();
        Account first = account("ЗанявшийОба");
        Account second = account("ЗанявшийОба2");
        call(post("/games/" + fixture.gameId + "/memberships/join"), first.token, Map.of());
        call(post("/games/" + fixture.gameId + "/memberships/join"), second.token, Map.of());
        call(post("/games/" + fixture.gameId + "/memberships/join"), watcher.token, Map.of());
        fixture.start();

        MvcResult refused = occupy(fixture, watcher, fixture.firstCharacter);

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(json.readTree(bodyOf(refused)).path("code").asText())
                .isEqualTo("observer_cannot_occupy");
    }

    @Test
    void theMasterCannotBeBannedOrLeave() throws Exception {
        Fixture fixture = fixture();
        fixture.ready();

        MvcResult banned = call(
                post("/games/" + fixture.gameId + "/memberships/ban"),
                fixture.master.token, Map.of("login", fixture.master.login));
        MvcResult left = call(
                post("/games/" + fixture.gameId + "/memberships/leave"),
                fixture.master.token, Map.of());

        assertThat(banned.getResponse().getStatus()).isEqualTo(409);
        assertThat(left.getResponse().getStatus())
                .as("мастер остаётся ответственным за игру (D-133)")
                .isEqualTo(409);
    }

    @Test
    void banIsScopedToOneGameAndUnbanReturnsTheRightToEnter() throws Exception {
        Fixture fixture = fixture();
        Account player = account("Бануемый");
        fixture.makePublic();
        fixture.ready();
        joinedMembership(fixture, player);
        fixture.start();
        occupy(fixture, player, fixture.firstCharacter);

        MvcResult banned = call(
                post("/games/" + fixture.gameId + "/memberships/ban"),
                fixture.master.token, Map.of("login", player.login));

        assertThat(banned.getResponse().getStatus()).isEqualTo(200);
        assertThat(fixture.statusOf(player.userId)).isEqualTo("BANNED");
        assertThat(fixture.isFree(fixture.firstCharacter))
                .as("бан освобождает слот (D-133)")
                .isTrue();

        MvcResult refused = call(
                post("/games/" + fixture.gameId + "/memberships/join"), player.token, Map.of());
        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(json.readTree(bodyOf(refused)).path("code").asText())
                .isEqualTo("membership_banned");

        MvcResult unbanned = call(
                post("/games/" + fixture.gameId + "/memberships/unban"),
                fixture.master.token, Map.of("login", player.login));
        assertThat(unbanned.getResponse().getStatus()).isEqualTo(200);
        assertThat(fixture.statusOf(player.userId))
                .as("снятие бана возвращает право входа, а не вход (D-133)")
                .isEqualTo("LEFT");
    }

    @Test
    void leavingFreesTheSlotButKeepsTheCharacterHistory() throws Exception {
        Fixture fixture = fixture();
        Account player = account("Уходящий");
        fixture.makePublic();
        fixture.ready();
        joinedMembership(fixture, player);
        fixture.start();
        occupy(fixture, player, fixture.firstCharacter);
        masterSays(fixture, fixture.place, "реплика до ухода");

        MvcResult left = call(
                post("/games/" + fixture.gameId + "/memberships/leave"), player.token, Map.of());

        assertThat(left.getResponse().getStatus()).isEqualTo(200);
        assertThat(fixture.statusOf(player.userId)).isEqualTo("LEFT");
        assertThat(fixture.isFree(fixture.firstCharacter))
                .as("выход освобождает слот")
                .isTrue();
        assertThat(countRows("SELECT count(*) FROM character WHERE id = ?", fixture.firstCharacter))
                .as("история персонажа сохраняется: физического удаления нет (D-009)")
                .isEqualTo(1);
    }

    @Test
    void aPlayerMovesOnlyAlongAnAvailableTransition() throws Exception {
        Fixture fixture = fixture();
        Account player = account("Идущий");
        fixture.makePublic();
        fixture.ready();
        joinedMembership(fixture, player);
        fixture.start();
        occupy(fixture, player, fixture.firstCharacter);
        UUID forward = mutations.createTransition(
                fixture.master.userId, fixture.gameId, fixture.place, fixture.elsewhere, "Тропа").id();

        MvcResult closed = call(
                post("/games/" + fixture.gameId + "/characters/" + fixture.firstCharacter + "/move"),
                player.token, Map.of("transitionId", forward.toString()));
        assertThat(closed.getResponse().getStatus())
                .as("неизвестный переход идти не даёт (REQUIREMENTS §8)")
                .isEqualTo(403);
        assertThat(json.readTree(bodyOf(closed)).path("code").asText())
                .isEqualTo("transition_unavailable");

        mutations.setEdgeState(fixture.master.userId, fixture.gameId, forward,
                fixture.firstCharacter, com.chattergame.chat.EdgeState.AVAILABLE, null);

        MvcResult moved = call(
                post("/games/" + fixture.gameId + "/characters/" + fixture.firstCharacter + "/move"),
                player.token, Map.of("transitionId", forward.toString()));

        assertThat(moved.getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject(
                        "SELECT chat_id FROM presence WHERE character_id = ? AND left_at IS NULL",
                        UUID.class, fixture.firstCharacter))
                .as("перемещение идёт по ребру, а не по названной клиентом локации")
                .isEqualTo(fixture.elsewhere);
    }

    private MvcResult occupy(Fixture fixture, Account account, UUID characterId) throws Exception {
        return call(put("/games/" + fixture.gameId + "/characters/" + characterId + "/occupation"),
                account.token, null);
    }

    /** Вход в публичную игру и проверка, что участие вошло: слот занимается отдельным жестом. */
    private UUID joinedMembership(Fixture fixture, Account account) throws Exception {
        call(post("/games/" + fixture.gameId + "/memberships/join"), account.token, Map.of());
        return fixture.membershipIdOf(account.userId);
    }
}
