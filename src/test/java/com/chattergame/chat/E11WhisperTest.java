package com.chattergame.chat;

import com.chattergame.character.Character;
import com.chattergame.message.MessageKind;
import com.chattergame.util.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Шёпот (`PLAN` E11, задача 2, `REQUIREMENTS §7.4`, `D-038`, `D-097`).
 *
 * <p>Главное отличие шёпота от остальных каналов — согласие: инициатор входит сразу,
 * остальные остаются приглашёнными, пока не ответят. Условия проверяются в момент
 * приглашения, поэтому отказ обязан называть разную причину: чужая локация, другая
 * скрытность и запрет речи — три разных состояния мира, и одно сообщение об ошибке
 * слило бы их.
 */
class E11WhisperTest extends E11TestSupport {

    @Test
    void whisperConditionsAreCheckedOneByOneWithDistinctReasons() throws Exception {
        Fixture fixture = runningGame();

        assertThatThrownBy(() -> mutations.createWhisper(
                fixture.first.userId, fixture.gameId,
                List.of(fixture.first.characterId, fixture.third.characterId)))
                .as("третий стоит в другой локации")
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo("whisper_same_location");

        fixture.hide(fixture.second.characterId, true);
        assertThatThrownBy(() -> mutations.createWhisper(
                fixture.first.userId, fixture.gameId,
                List.of(fixture.first.characterId, fixture.second.characterId)))
                .as("второй скрыт, а первый — нет")
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo("whisper_same_stealth");
        fixture.hide(fixture.second.characterId, false);

        mute(fixture, fixture.second.characterId);
        assertThatThrownBy(() -> mutations.createWhisper(
                fixture.first.userId, fixture.gameId,
                List.of(fixture.first.characterId, fixture.second.characterId)))
                .as("второму запрещена речь")
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo("whisper_speech_restricted");
    }

    @Test
    void theInitiatorEntersAndTheOthersAreInvited() throws Exception {
        Fixture fixture = runningGame();
        Chat whisper = mutations.createWhisper(
                fixture.first.userId, fixture.gameId,
                List.of(fixture.first.characterId, fixture.second.characterId));

        assertThat(countRows(
                        "SELECT count(*) FROM chat_member WHERE chat_id = ? AND subject_id = ? "
                                + "AND state = 'JOINED'",
                        whisper.id(), fixture.first.characterId))
                .as("инициатор входит сразу")
                .isEqualTo(1);
        assertThat(countRows(
                        "SELECT count(*) FROM chat_member WHERE chat_id = ? AND subject_id = ? "
                                + "AND state = 'INVITED'",
                        whisper.id(), fixture.second.characterId))
                .as("второй ждёт согласия (D-038)")
                .isEqualTo(1);
        assertThat(countRows(
                        "SELECT count(*) FROM chat_member WHERE chat_id = ? AND subject_kind = 'MEMBERSHIP' "
                                + "AND state = 'JOINED'",
                        whisper.id()))
                .as("мастер состоит по умолчанию")
                .isEqualTo(1);
    }

    @Test
    void acceptingTurnsAnInvitationIntoParticipation() throws Exception {
        Fixture fixture = runningGame();
        Chat whisper = mutations.createWhisper(
                fixture.first.userId, fixture.gameId,
                List.of(fixture.first.characterId, fixture.second.characterId));

        assertThatThrownBy(() -> writes.post(
                fixture.second.userId, fixture.gameId, whisper.id(), fixture.second.characterId,
                MessageKind.SPEECH, "ещё не согласился", false))
                .as("приглашённый до согласия в чат не пишет")
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo("not_a_chat_member");

        mutations.acceptWhisper(fixture.second.userId, fixture.gameId, whisper.id());
        writes.post(fixture.second.userId, fixture.gameId, whisper.id(), fixture.second.characterId,
                MessageKind.SPEECH, "согласен", false);
    }

    @Test
    void decliningIsRecordedAndKeepsTheInvitationClosed() throws Exception {
        Fixture fixture = runningGame();
        Chat whisper = mutations.createWhisper(
                fixture.first.userId, fixture.gameId,
                List.of(fixture.first.characterId, fixture.second.characterId));

        mutations.declineWhisper(fixture.second.userId, fixture.gameId, whisper.id());

        assertThat(countRows(
                        "SELECT count(*) FROM chat_member WHERE chat_id = ? AND subject_id = ? "
                                + "AND state = 'DECLINED'",
                        whisper.id(), fixture.second.characterId))
                .as("отказ — состояние, а не тишина")
                .isEqualTo(1);
        assertThatThrownBy(() -> writes.post(
                fixture.second.userId, fixture.gameId, whisper.id(), fixture.second.characterId,
                MessageKind.SPEECH, "передумал", false))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo("not_a_chat_member");
    }

    @Test
    void whisperSurvivesLeavingTheLocation() throws Exception {
        Fixture fixture = runningGame();
        Chat whisper = mutations.createWhisper(
                fixture.first.userId, fixture.gameId,
                List.of(fixture.first.characterId, fixture.second.characterId));
        mutations.acceptWhisper(fixture.second.userId, fixture.gameId, whisper.id());

        fixture.move(fixture.first.characterId, fixture.elsewhere);

        writes.post(fixture.second.userId, fixture.gameId, whisper.id(), fixture.second.characterId,
                MessageKind.SPEECH, "отошли в сторону", false);
        assertThat(channels.requireVisible(fixture.first.userId, fixture.gameId, whisper.id()).chat().archived())
                .as("уход из локации шёпот не разрывает (D-038)")
                .isFalse();
    }

    @Test
    void twoCharactersOfOnePlayerMayWhisperTogetherWithAnotherPlayer() throws Exception {
        Fixture fixture = runningGame();
        UUID second = secondCharacter(fixture, fixture.first);

        Chat whisper = mutations.createWhisper(
                fixture.first.userId, fixture.gameId,
                List.of(fixture.first.characterId, second, fixture.second.characterId));

        assertThat(channels.participants(fixture.gameId, whisper.id()))
                .as("два персонажа одного игрока в шёпоте допустимы (D-097)")
                .hasSize(4);
    }

    @Test
    void aWhisperOfOnePlayersCharactersAloneIsRefused() throws Exception {
        Fixture fixture = runningGame();
        UUID second = secondCharacter(fixture, fixture.first);

        assertThatThrownBy(() -> mutations.createWhisper(
                fixture.first.userId, fixture.gameId,
                List.of(fixture.first.characterId, second)))
                .as("шёпот с самим собой не нужен: для этого есть личный чат (D-097)")
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo("whisper_players");
    }

    @Test
    void theMasterReadsTheWhisperAndJokesAreAllowedByDefault() throws Exception {
        Fixture fixture = runningGame();
        Chat whisper = mutations.createWhisper(
                fixture.first.userId, fixture.gameId,
                List.of(fixture.first.characterId, fixture.second.characterId));
        mutations.acceptWhisper(fixture.second.userId, fixture.gameId, whisper.id());

        writes.post(fixture.first.userId, fixture.gameId, whisper.id(), fixture.first.characterId,
                MessageKind.SPEECH, "тихо", false);
        writes.post(fixture.first.userId, fixture.gameId, whisper.id(), fixture.first.characterId,
                MessageKind.JOKE, "и с шуткой по умолчанию", false);

        assertThat(reads.feed(fixture.masterUserId, fixture.gameId, whisper.id(), null, 0, 0)
                        .items().stream()
                        .map(com.chattergame.message.FeedItem::body)
                        .filter(java.util.Objects::nonNull)
                        .toList())
                .as("мастер состоит по умолчанию и видит ленту (§7.4, D-038)")
                .containsExactly("тихо", "и с шуткой по умолчанию");
        assertThat(countEvents(fixture.gameId, "CHAT_MEMBER_INVITED")).isEqualTo(1);
        assertThat(countEvents(fixture.gameId, "CHAT_MEMBER_JOINED"))
                .as("согласие пишет событие входа, а вступление инициатора — нет: он уже внутри")
                .isEqualTo(1);
    }

    @Test
    void anOutsiderDoesNotSeeTheWhisper() throws Exception {
        Fixture fixture = runningGame();
        Chat whisper = mutations.createWhisper(
                fixture.first.userId, fixture.gameId,
                List.of(fixture.first.characterId, fixture.second.characterId));

        assertThatThrownBy(() -> channels.requireVisible(
                fixture.third.userId, fixture.gameId, whisper.id()))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo("not_a_chat_member");
    }

    @Test
    void closingAWhisperArchivesItAndKeepsHistoryReadable() throws Exception {
        Fixture fixture = runningGame();
        Chat whisper = mutations.createWhisper(
                fixture.first.userId, fixture.gameId,
                List.of(fixture.first.characterId, fixture.second.characterId));
        mutations.acceptWhisper(fixture.second.userId, fixture.gameId, whisper.id());
        writes.post(fixture.first.userId, fixture.gameId, whisper.id(), fixture.first.characterId,
                MessageKind.SPEECH, "остаётся в истории", false);

        mutations.closeWhisper(fixture.first.userId, fixture.gameId, whisper.id());

        assertThatThrownBy(() -> writes.post(
                fixture.first.userId, fixture.gameId, whisper.id(), fixture.first.characterId,
                MessageKind.SPEECH, "после закрытия", false))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo("chat_archived");
        assertThat(feedBodies(fixture, whisper.id(), fixture.first))
                .as("история закрытого шёпота читается")
                .containsExactly("остаётся в истории");
        assertThat(countEvents(fixture.gameId, "CHAT_ARCHIVED")).isEqualTo(1);
    }

    /** Второй персонаж того же игрока: им проверяется верхний предел участников (`D-097`). */
    private UUID secondCharacter(Fixture fixture, Actor actor) {
        Character extra = mutations.createCharacter(
                fixture.masterUserId, fixture.gameId, "Двойник-" + unique(""),
                null, null, null, null, true, false);
        mutations.placeCharacter(fixture.masterUserId, fixture.gameId, extra.id(), fixture.place);
        jdbc.update("UPDATE character SET occupant_membership_id = ? WHERE id = ?",
                actor.membershipId, extra.id());
        return extra.id();
    }
}
