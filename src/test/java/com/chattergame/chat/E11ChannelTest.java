package com.chattergame.chat;

import com.chattergame.message.MessageKind;
import com.chattergame.util.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Личный чат с мастером и список каналов (`PLAN` E11, задачи 1 и 5).
 *
 * <p>Проверяется то, что отличает личный канал от локации: он заводится входом в игру, а не
 * мастером (`D-037`); бюджета не расходует (`D-013`); в списке каналов одного игрока нет
 * чужого разговора, а у наблюдателя каналов нет вовсе (`D-063`).
 */
class E11ChannelTest extends E11TestSupport {

    @Test
    void joiningTheGameOpensAPersonalChatWithTheMaster() throws Exception {
        Fixture fixture = runningGame();

        for (UUID membershipId : List.of(
                fixture.first.membershipId, fixture.second.membershipId, fixture.third.membershipId)) {
            UUID dm = masterDmOf(fixture, membershipId);
            assertThat(countRows(
                            "SELECT count(*) FROM chat_member WHERE chat_id = ? AND state = 'JOINED'",
                            dm))
                    .as("в личном чате обе стороны разговора: игрок и мастер (D-037)")
                    .isEqualTo(2);
        }
        assertThat(countRows(
                        "SELECT count(*) FROM chat WHERE game_id = ? AND kind = 'MASTER_DM'",
                        fixture.gameId))
                .as("на каждого вошедшего игрока — свой личный чат")
                .isEqualTo(3);
    }

    @Test
    void thePersonalChatCarriesSpeechOfAnyCharactersOfThePlayer() throws Exception {
        Fixture fixture = runningGame();
        UUID dm = masterDmOf(fixture, fixture.first.membershipId);
        UUID second = extraCharacter(fixture, fixture.first);

        writes.post(fixture.first.userId, fixture.gameId, dm, fixture.first.characterId,
                MessageKind.SPEECH, "от первого", false);
        writes.post(fixture.first.userId, fixture.gameId, dm, second,
                MessageKind.SPEECH, "от второго", false);

        assertThat(jdbc.queryForList(
                        "SELECT DISTINCT character_id FROM message WHERE chat_id = ?", UUID.class, dm))
                .as("выбор персонажа влияет только на подпись в ленте (§7.3)")
                .containsExactlyInAnyOrder(fixture.first.characterId, second);
        assertThat(channels.requireVisible(fixture.first.userId, fixture.gameId, dm).chat().title())
                .as("в UI игрока чат подписан «Мастер» (§7.3)")
                .isEqualTo("Мастер");
    }

    /** Второй персонаж того же игрока: им проверяется подпись в личном чате (§7.3). */
    private UUID extraCharacter(Fixture fixture, Actor actor) {
        com.chattergame.character.Character extra = mutations.createCharacter(
                fixture.masterUserId, fixture.gameId, "Второй-лицо-" + unique(""),
                null, null, null, null, true, false);
        jdbc.update("UPDATE character SET occupant_membership_id = ? WHERE id = ?",
                actor.membershipId, extra.id());
        return extra.id();
    }

    @Test
    void thePersonalChatDoesNotConsumeTheTurnBudget() throws Exception {
        Fixture fixture = runningGame();
        UUID dm = masterDmOf(fixture, fixture.first.membershipId);
        mutations.setTurnBased(fixture.masterUserId, fixture.gameId, true);
        mutations.openTurn(fixture.masterUserId, fixture.gameId);

        writes.post(fixture.first.userId, fixture.gameId, dm, fixture.first.characterId,
                MessageKind.SPEECH, "личное раз", false);
        writes.post(fixture.first.userId, fixture.gameId, dm, fixture.first.characterId,
                MessageKind.SPEECH, "личное два", false);

        assertThat(countRows(
                        "SELECT count(*) FROM turn_budget b JOIN turn t ON t.id = b.turn_id "
                                + "WHERE t.game_id = ?",
                        fixture.gameId))
                .as("личный чат не тратит и не заводит строк бюджета (D-013)")
                .isZero();
    }

    @Test
    void theMasterReadsThePlayersPersonalMessages() throws Exception {
        Fixture fixture = runningGame();
        UUID dm = masterDmOf(fixture, fixture.first.membershipId);

        writes.post(fixture.first.userId, fixture.gameId, dm, fixture.first.characterId,
                MessageKind.SPEECH, "вопрос мастеру", false);

        List<String> seenByMaster = reads
                .feed(fixture.masterUserId, fixture.gameId, dm, null, 0, 0).items().stream()
                .map(com.chattergame.message.FeedItem::body)
                .filter(java.util.Objects::nonNull)
                .toList();
        assertThat(seenByMaster)
                .as("личный чат адресован участию обеих сторон (D-037), и мастер его читает")
                .contains("вопрос мастеру");
    }

    @Test
    void aPlayerDoesNotSeeThePersonalChatOfAnotherPlayer() throws Exception {
        Fixture fixture = runningGame();

        List<ChannelQueryService.ChannelAccess> mine =
                channels.channels(fixture.second.userId, fixture.gameId);

        assertThat(mine.stream().map(access -> access.chat().id()).toList())
                .as("чужой личный чат в свой список не попадает")
                .containsExactly(masterDmOf(fixture, fixture.second.membershipId));
    }

    @Test
    void theObserverHasNoChannelsAtAll() throws Exception {
        Fixture fixture = runningGame();
        Actor observer = observer(fixture);

        assertThat(channels.channels(observer.userId, fixture.gameId))
                .as("наблюдателю закрытые каналы не видны (D-063)")
                .isEmpty();
        assertThat(call(get("/games/{gameId}/channels", fixture.gameId), observer.token, null)
                        .getResponse().getContentAsString())
                .as("и по HTTP список пуст")
                .isEqualTo("[]");
    }

    @Test
    void readingAChannelWithoutMembershipIsRefused() throws Exception {
        Fixture fixture = runningGame();
        UUID foreign = masterDmOf(fixture, fixture.first.membershipId);

        assertThatThrownBy(() -> channels.requireVisible(fixture.second.userId, fixture.gameId, foreign))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo("not_a_chat_member");
    }

    @Test
    void theChannelListEndpointCountsUnreadFromInboxEntries() throws Exception {
        Fixture fixture = runningGame();
        UUID dm = masterDmOf(fixture, fixture.first.membershipId);
        // Начало разговора читается прочитанным: проверяется непрочитанное от одной реплики,
        // а не от системной проекции создания чата.
        call(post("/games/{gameId}/chats/{chatId}/read", fixture.gameId, dm),
                fixture.first.token, null);
        writes.post(fixture.first.userId, fixture.gameId, dm, fixture.first.characterId,
                MessageKind.SPEECH, "сообщение в личном чате", false);

        var listed = call(get("/games/{gameId}/channels", fixture.gameId), fixture.first.token, null);
        assertThat(listed.getResponse().getStatus()).isEqualTo(200);
        assertThat(listed.getResponse().getContentAsString())
                .as("счётчик непрочитанного выводится из inbox_entry (D-036)")
                .contains("\"unread\":1");

        call(post("/games/{gameId}/chats/{chatId}/read", fixture.gameId, dm),
                fixture.first.token, null);
        assertThat(call(get("/games/{gameId}/channels", fixture.gameId), fixture.first.token, null)
                        .getResponse().getContentAsString())
                .as("отметка о прочтении двигает счётчик, а не сбрасывает его полем")
                .contains("\"unread\":0");

        assertThat(call(get("/games/{gameId}/channels", fixture.gameId), null, null)
                        .getResponse().getStatus())
                .as("без сессии канал не читается")
                .isEqualTo(401);
    }
}
