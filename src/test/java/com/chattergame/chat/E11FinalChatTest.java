package com.chattergame.chat;

import com.chattergame.character.Character;
import com.chattergame.game.Game;
import com.chattergame.game.GameTransition;
import com.chattergame.message.MessageKind;
import com.chattergame.util.ApiException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Чат финального обсуждения (`PLAN` E11, задача 4, `REQUIREMENTS §7.5`, `D-094`).
 *
 * <p>Чат один на игру и открывается завершением, а не отдельным действием мастера: он часть
 * перехода, и журнал обязан воспроизводить его из `GAME_FINISHED` (`D-033`). После
 * `FINISHED` мир read-only, поэтому проверяется пара свойств сразу: все чаты читаются, а
 * пишут все только в финальный.
 */
class E11FinalChatTest extends E11TestSupport {

    @Test
    void finishingOpensTheOnlyFinalChatAndCarriesEveryCharacter() throws Exception {
        Fixture fixture = runningGame();
        UUID archived = archivedCharacter(fixture);

        Game finished = mutations.applyTransition(
                fixture.masterUserId, fixture.gameId, GameTransition.FINISH, null);

        assertThat(finished.finalChatId()).isNotNull();
        assertThat(countRows(
                        "SELECT count(*) FROM chat WHERE game_id = ? AND kind = 'FINAL'",
                        fixture.gameId))
                .as("чат финального обсуждения один на игру")
                .isEqualTo(1);
        assertThat(countRows(
                        "SELECT count(*) FROM chat_member WHERE chat_id = ? AND state = 'JOINED'",
                        finished.finalChatId()))
                .as("внутри три живых персонажа и мастер, архивированный не переносится (D-094)")
                .isEqualTo(4);
        assertThat(countRows(
                        "SELECT count(*) FROM chat_member WHERE chat_id = ? AND subject_id = ?",
                        finished.finalChatId(), archived))
                .as("архивированного персонажа в финальном чате нет")
                .isZero();
    }

    @Test
    void afterTheFinishEveryChatIsReadableButOnlyTheFinalOneAcceptsMessages() throws Exception {
        Fixture fixture = runningGame();
        writes.post(fixture.first.userId, fixture.gameId, fixture.place, fixture.first.characterId,
                MessageKind.SPEECH, "последняя реплика перед финалом", false);
        Chat radio = mutations.createRadio(
                fixture.masterUserId, fixture.gameId, "Волна", java.util.List.of(fixture.first.characterId));

        Game finished = mutations.applyTransition(
                fixture.masterUserId, fixture.gameId, GameTransition.FINISH, null);
        UUID finalChatId = finished.finalChatId();

        assertThat(feedBodies(fixture, fixture.place, fixture.first))
                .as("история локации читается и после конца игры")
                .contains("последняя реплика перед финалом");
        assertThat(feedBodies(fixture, radio.id(), fixture.first))
                .as("история радиочата читается тоже")
                .isEmpty();

        assertThatThrownBy(() -> writes.post(
                fixture.first.userId, fixture.gameId, fixture.place, fixture.first.characterId,
                MessageKind.SPEECH, "ещё словечко", false))
                .as("в локацию после FINISHED писать нельзя (D-094)")
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo("game_finished");

        writes.post(fixture.first.userId, fixture.gameId, finalChatId, fixture.first.characterId,
                MessageKind.JOKE, "спасибо за игру", false);
        assertThatThrownBy(() -> writes.post(
                fixture.first.userId, fixture.gameId, finalChatId, fixture.first.characterId,
                MessageKind.SPEECH, "а можно речью?", false))
                .as("в финальном обсуждении — только шутка и реплика мастера")
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo("final_joke_only");

        writes.post(fixture.masterUserId, fixture.gameId, finalChatId, null,
                MessageKind.MASTER, "игра окончена", false);
    }

    @Test
    void theObserverReadsAndJokesInTheFinalChatOnly() throws Exception {
        Fixture fixture = runningGame();
        Actor observer = observer(fixture);
        writes.post(fixture.first.userId, fixture.gameId, fixture.place, fixture.first.characterId,
                MessageKind.SPEECH, "до финала", false);

        Game finished = mutations.applyTransition(
                fixture.masterUserId, fixture.gameId, GameTransition.FINISH, null);
        UUID finalChatId = finished.finalChatId();

        assertThat(channels.channels(observer.userId, fixture.gameId).stream()
                        .map(access -> access.chat().id()).toList())
                .as("наблюдатель видит только открытый финальный чат (D-063)")
                .containsExactly(finalChatId);

        writes.post(observer.userId, fixture.gameId, finalChatId, null,
                MessageKind.JOKE, "наблюдаю и шучу", false);
        assertThatThrownBy(() -> writes.post(
                observer.userId, fixture.gameId, fixture.place, null,
                MessageKind.SPEECH, "нельзя", false))
                .isInstanceOf(ApiException.class);
    }

    /** Архивированный персонаж: финальный чат переносит живых, а не всех подряд. */
    private UUID archivedCharacter(Fixture fixture) {
        Character character = mutations.createCharacter(
                fixture.masterUserId, fixture.gameId, "Ушедший-" + unique(""),
                null, null, null, null, true, false);
        mutations.placeCharacter(fixture.masterUserId, fixture.gameId, character.id(), fixture.elsewhere);
        mutations.archiveCharacter(fixture.masterUserId, fixture.gameId, character.id(), "вышел из игры");
        return character.id();
    }
}
