package com.chattergame.game;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Приглашения в приватную игру (`PLAN` E13, задача 1; `D-130`, `D-131`).
 *
 * <p>Проверяется граница между «позвали» и «вошёл»: приглашение бронирует слот, но не
 * занимает его, а отказ возвращает слот в игру. Без этого различия отказавшийся игрок
 * держал бы персонажа занятым навсегда, и это не поймал бы ни один тест занятия.
 */
class E13InvitationTest extends E13TestSupport {

    @Test
    void invitationToUnknownLoginIsRefusedAndCreatesNothing() throws Exception {
        Fixture fixture = fixture();
        fixture.ready();

        MvcResult refused = invite(fixture, fixture.master.token, unique("нетТакого"), "PLAYER", null);

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(refused)).isEqualTo("unknown_login");
        assertThat(countRows(
                        "SELECT count(*) FROM membership WHERE game_id = ? AND status = 'INVITED'",
                        fixture.gameId))
                .as("несуществующий логин не заводит строку участия (D-131)")
                .isZero();
    }

    @Test
    void invitationReservesTheSlotWithoutOccupyingIt() throws Exception {
        Fixture fixture = fixture();
        Account player = account("Приглашённый");
        fixture.ready();

        MvcResult invited = invite(
                fixture, fixture.master.token, player.login, "PLAYER", fixture.firstCharacter);

        assertThat(invited.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(bodyOf(invited)).path("status").asText()).isEqualTo("INVITED");
        assertThat(fixture.statusOf(player.userId)).isEqualTo("INVITED");
        assertThat(fixture.reservedOf(player.userId))
                .as("бронь живёт в приглашении")
                .isEqualTo(fixture.firstCharacter);
        assertThat(fixture.isFree(fixture.firstCharacter))
                .as("INVITED слот не занимает (D-130)")
                .isTrue();
    }

    @Test
    void acceptTakesTheReservedSlotAndMovesTheMembershipToJoined() throws Exception {
        Fixture fixture = fixture();
        Account player = account("Соглашающийся");
        fixture.ready();
        invite(fixture, fixture.master.token, player.login, "PLAYER", fixture.firstCharacter);

        MvcResult accepted = call(
                post("/games/" + fixture.gameId + "/memberships/invitation/accept"),
                player.token, Map.of());

        assertThat(accepted.getResponse().getStatus()).isEqualTo(200);
        assertThat(fixture.statusOf(player.userId)).isEqualTo("JOINED");
        assertThat(fixture.occupantOf(fixture.firstCharacter))
                .as("слот занимается согласием (D-130)")
                .isEqualTo(fixture.membershipIdOf(player.userId));
        assertThat(fixture.reservedOf(player.userId))
                .as("бронь стала занятостью и снята")
                .isNull();
    }

    @Test
    void declineFreesTheSlotForAnotherInvitation() throws Exception {
        Fixture fixture = fixture();
        Account first = account("Отказывающийся");
        Account second = account("Следующий");
        fixture.ready();
        invite(fixture, fixture.master.token, first.login, "PLAYER", fixture.firstCharacter);

        MvcResult declined = call(
                post("/games/" + fixture.gameId + "/memberships/invitation/decline"),
                first.token, Map.of());

        assertThat(declined.getResponse().getStatus()).isEqualTo(200);
        assertThat(fixture.statusOf(first.userId))
                .as("отказ — отдельный статус, а не выход (D-123)")
                .isEqualTo("DECLINED");
        assertThat(fixture.reservedOf(first.userId)).isNull();

        MvcResult reinvited = invite(
                fixture, fixture.master.token, second.login, "PLAYER", fixture.firstCharacter);
        assertThat(reinvited.getResponse().getStatus())
                .as("после отказа слот снова можно обещать")
                .isEqualTo(200);
        assertThat(fixture.reservedOf(second.userId)).isEqualTo(fixture.firstCharacter);
    }

    @Test
    void oneSlotCannotBeReservedTwice() throws Exception {
        Fixture fixture = fixture();
        Account first = account("ПервыйБронирующий");
        Account second = account("ВторойБронирующий");
        fixture.ready();
        invite(fixture, fixture.master.token, first.login, "PLAYER", fixture.firstCharacter);

        MvcResult refused = invite(
                fixture, fixture.master.token, second.login, "PLAYER", fixture.firstCharacter);

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(refused)).isEqualTo("character_reserved");
        assertThat(countRows(
                        "SELECT count(*) FROM membership WHERE game_id = ? AND user_id = ?",
                        fixture.gameId, second.userId))
                .as("отказ в брони не заводит участия второму игроку")
                .isZero();
    }

    @Test
    void onlyTheMasterInvites() throws Exception {
        Fixture fixture = fixture();
        Account player = account("ИгрокПриглашающий");
        Account target = account("ЦельПриглашения");
        fixture.ready();
        invite(fixture, fixture.master.token, player.login, "PLAYER", null);
        call(post("/games/" + fixture.gameId + "/memberships/invitation/accept"), player.token, Map.of());

        MvcResult refused = invite(
                fixture, player.token, target.login, "PLAYER", fixture.secondCharacter);

        assertThat(refused.getResponse().getStatus())
                .as("игрок вход не открывает никому (D-131)")
                .isEqualTo(403);
        assertThat(codeOf(refused)).isEqualTo("not_master");
    }

    @Test
    void invitationToRunningGameIsAllowedAndBackfillsTheObservedHistory() throws Exception {
        Fixture fixture = fixture();
        Account watcher = account("ОпоздавшийНаблюдатель");
        fixture.ready();
        fixture.start();
        masterSays(fixture, fixture.place, "история до приглашения");

        MvcResult invited = invite(fixture, fixture.master.token, watcher.login, "OBSERVER", null);
        assertThat(invited.getResponse().getStatus())
                .as("приглашение в RUNNING разрешено и отличается досчётом (D-131)")
                .isEqualTo(200);
        call(post("/games/" + fixture.gameId + "/memberships/invitation/accept"),
                watcher.token, Map.of());

        backfill.runNow();
        assertThat(inboxRows(fixture.membershipIdOf(watcher.userId), fixture.place))
                .as("наблюдателю материализуется история открытой локации, а не пустая лента (D-086)")
                .isGreaterThan(0);
    }

    @Test
    void theMasterListsTheWholeRosterAndThePlayerOnlyHimself() throws Exception {
        Fixture fixture = fixture();
        Account player = account("СмотрящийСостав");
        fixture.ready();
        invite(fixture, fixture.master.token, player.login, "PLAYER", fixture.firstCharacter);
        call(post("/games/" + fixture.gameId + "/memberships/invitation/accept"),
                player.token, Map.of());

        MvcResult asMaster = call(
                get("/games/" + fixture.gameId + "/memberships"), fixture.master.token, null);
        JsonNode roster = json.readTree(bodyOf(asMaster));
        assertThat(roster.size()).as("мастеру виден весь состав (§6): мастер и игрок").isEqualTo(2);

        MvcResult asPlayer = call(
                get("/games/" + fixture.gameId + "/memberships"), player.token, null);
        JsonNode myself = json.readTree(bodyOf(asPlayer));
        assertThat(myself.size()).isEqualTo(1);
        assertThat(myself.get(0).path("userId").asText()).isEqualTo(player.userId.toString());
    }

    @Test
    void observingInvitationCannotReserveASlot() throws Exception {
        Fixture fixture = fixture();
        Account watcher = account("НаблюдательПриглашённый");
        fixture.ready();

        MvcResult refused = invite(
                fixture, fixture.master.token, watcher.login, "OBSERVER", fixture.firstCharacter);

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(refused)).isEqualTo("observer_cannot_reserve");
    }

    @Test
    void anObserverInvitationOpensAPrivateGameForReading() throws Exception {
        Fixture fixture = fixture();
        Account watcher = account("НаблюдательПриватной");
        fixture.ready();
        fixture.start();

        assertThat(invite(fixture, fixture.master.token, watcher.login, "OBSERVER", null)
                .getResponse().getStatus())
                .as("наблюдателем в приватную игру пускают приглашением (D-131)")
                .isEqualTo(200);
        call(post("/games/" + fixture.gameId + "/memberships/invitation/accept"),
                watcher.token, Map.of());

        assertThat(fixture.statusOf(watcher.userId)).isEqualTo("JOINED");
        assertThat(jdbc.queryForObject(
                        "SELECT role FROM membership WHERE game_id = ? AND user_id = ?",
                        String.class, fixture.gameId, watcher.userId))
                .isEqualTo("OBSERVER");
    }

    private MvcResult invite(Fixture fixture, String token, String login, String role, UUID characterId)
            throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("login", login);
        payload.put("role", role);
        if (characterId != null) {
            payload.put("characterId", characterId.toString());
        }
        return call(post("/games/" + fixture.gameId + "/memberships/invitations"), token, body(payload));
    }

    private static Map<String, ?> body(Map<String, Object> payload) {
        return payload;
    }

    private String codeOf(MvcResult result) throws Exception {
        return json.readTree(bodyOf(result)).path("code").asText();
    }
}
