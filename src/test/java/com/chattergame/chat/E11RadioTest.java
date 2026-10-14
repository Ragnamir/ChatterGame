package com.chattergame.chat;

import com.chattergame.message.MessageKind;
import com.chattergame.util.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Радиочат (`PLAN` E11, задача 3, `REQUIREMENTS §7.2`, `D-039`).
 *
 * <p>Радио — фиксированный круг персонажей, а не место: участники не обязаны стоять в одной
 * локации. Отсюда две проверки, которых нет у локации: речь проходит сквозь расстояния, но
 * `ACTION` и `JOKE` в нём запрещены. Отключённый мастером канал замолкает, но история
 * остаётся читаемой (`D-010`).
 */
class E11RadioTest extends E11TestSupport {

    @Test
    void radioSpeaksAcrossLocationsAndRefusesAnythingButSpeech() throws Exception {
        Fixture fixture = runningGame();
        // Первый и третий — в разных локациях: радио связывает не место (§7.2).
        Chat radio = mutations.createRadio(
                fixture.masterUserId,
                fixture.gameId,
                "Волна",
                List.of(fixture.first.characterId, fixture.third.characterId));

        writes.post(fixture.third.userId, fixture.gameId, radio.id(), fixture.third.characterId,
                MessageKind.SPEECH, "на связи", false);

        assertThatThrownBy(() -> writes.post(
                fixture.first.userId, fixture.gameId, radio.id(), fixture.first.characterId,
                MessageKind.ACTION, "жест", false))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo("radio_speech_only");
        assertThatThrownBy(() -> writes.post(
                fixture.first.userId, fixture.gameId, radio.id(), fixture.first.characterId,
                MessageKind.JOKE, "шутка", false))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo("radio_speech_only");
    }

    @Test
    void replacingRadioMembersRemovesTheOldOnes() throws Exception {
        Fixture fixture = runningGame();
        Chat radio = mutations.createRadio(
                fixture.masterUserId,
                fixture.gameId,
                "Волна",
                List.of(fixture.first.characterId, fixture.second.characterId));

        mutations.setRadioMembers(
                fixture.masterUserId, fixture.gameId, radio.id(), List.of(fixture.second.characterId));

        assertThatThrownBy(() -> writes.post(
                fixture.first.userId, fixture.gameId, radio.id(), fixture.first.characterId,
                MessageKind.SPEECH, "ещё слышу", false))
                .as("убранный из списка теряет право речи")
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo("not_a_chat_member");
        writes.post(fixture.second.userId, fixture.gameId, radio.id(), fixture.second.characterId,
                MessageKind.SPEECH, "на связи", false);
    }

    @Test
    void aDisabledRadioKeepsItsHistoryButRefusesNewSpeech() throws Exception {
        Fixture fixture = runningGame();
        Chat radio = mutations.createRadio(
                fixture.masterUserId, fixture.gameId, "Волна", List.of(fixture.first.characterId));
        writes.post(fixture.first.userId, fixture.gameId, radio.id(), fixture.first.characterId,
                MessageKind.SPEECH, "до отключения", false);

        mutations.setRadioDisabled(fixture.masterUserId, fixture.gameId, radio.id(), true);

        assertThatThrownBy(() -> writes.post(
                fixture.first.userId, fixture.gameId, radio.id(), fixture.first.characterId,
                MessageKind.SPEECH, "после отключения", false))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo("chat_disabled");
        assertThat(feedBodies(fixture, radio.id(), fixture.first))
                .as("история связи читается и у отключённого канала (D-010)")
                .containsExactly("до отключения");
    }

    @Test
    void radioIsCreatedWithEventsAndIsVisibleOnlyToItsMembers() throws Exception {
        Fixture fixture = runningGame();
        Chat radio = mutations.createRadio(
                fixture.masterUserId, fixture.gameId, "Волна", List.of(fixture.first.characterId));

        assertThat(fixture.channelKindOf(radio.id())).isEqualTo("RADIO");
        assertThatThrownBy(() -> channels.requireVisible(fixture.second.userId, fixture.gameId, radio.id()))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo("not_a_chat_member");
        assertThat(channels.requireVisible(fixture.masterUserId, fixture.gameId, radio.id()).master())
                .as("мастеру канал игры виден")
                .isTrue();
    }

    @Test
    void aRadioMemberListCannotRepeatACharacter() throws Exception {
        Fixture fixture = runningGame();

        assertThatThrownBy(() -> mutations.createRadio(
                fixture.masterUserId,
                fixture.gameId,
                "Волна",
                List.of(fixture.first.characterId, fixture.first.characterId)))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo("channel_member_duplicate");
    }
}
