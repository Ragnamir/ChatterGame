package com.chattergame.game;

import com.chattergame.message.MessageKind;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Пауза, архив и валидация готовности (`PLAN` E13, задачи 5–6; `D-010`, `D-059`).
 *
 * <p>Пауза меняет только отображение: права считаются по фазе, а не по признаку паузы,
 * и проверка «после паузы игрок всё ещё пишет» — единственная, которая отличает одно
 * от другого. Архив, наоборот, обязан оставить игру читаемой и не удалить ни строки:
 * физического удаления нет ни до `READY`, ни после (`D-010`).
 */
class E13LifecycleTest extends E13TestSupport {

    @Test
    void pauseRequiresAReason() throws Exception {
        Fixture fixture = fixture();
        fixture.ready();
        fixture.start();

        assertThatThrownBy(() -> mutations.applyTransition(
                fixture.master.userId, fixture.gameId, GameTransition.PAUSE, "  "))
                .hasMessageContaining("причину паузы");
    }

    @Test
    void pauseStopsShowingTheGameButNotTheRights() throws Exception {
        Fixture fixture = fixture();
        Account player = account("НаПаузе");
        fixture.makePublic();
        fixture.ready();
        fixture.start();
        call(post("/games/" + fixture.gameId + "/memberships/join"), player.token, java.util.Map.of());

        mutations.applyTransition(fixture.master.userId, fixture.gameId, GameTransition.PAUSE, "перерыв");
        assertThat(pausedOf(fixture.gameId)).isTrue();

        UUID character = jdbc.queryForObject(
                "SELECT id FROM character WHERE occupant_membership_id = ?",
                UUID.class, fixture.membershipIdOf(player.userId));
        writes.post(player.userId, fixture.gameId, fixture.place, character,
                MessageKind.SPEECH, "игра на паузе, но право писать прежнее", false);

        assertThat(countRows(
                        "SELECT count(*) FROM message WHERE chat_id = ? AND body = ?",
                        fixture.place, "игра на паузе, но право писать прежнее"))
                .as("пауза не меняет права, только отображение (D-059)")
                .isEqualTo(1);
    }

    @Test
    void archivedGameStaysReadableAndNothingIsDeleted() throws Exception {
        Fixture fixture = fixture();
        fixture.ready();

        int archived = mutations.archiveAllOfMaster(fixture.master.userId, "игра больше не нужна");
        assertThat(archived).isGreaterThanOrEqualTo(1);
        assertThat(archivedOf(fixture.gameId)).isTrue();

        assertThat(countRows("SELECT count(*) FROM game WHERE id = ?", fixture.gameId))
                .as("архив — это признак, а не удаление (D-010)")
                .isEqualTo(1);
        assertThat(countRows("SELECT count(*) FROM chat WHERE game_id = ?", fixture.gameId))
                .isEqualTo(2);

        mutations.archiveAllOfMaster(fixture.master.userId, "повторно");
        assertThat(countRows(
                        "SELECT count(*) FROM game_event WHERE game_id = ? AND type = 'GAME_ARCHIVED'",
                        fixture.gameId))
                .as("повторная архивация не удваивает журнал")
                .isEqualTo(1);
    }

    @Test
    void readinessIsRevalidatedOnEveryAttemptToBecomeReady() throws Exception {
        Fixture fixture = fixture();
        UUID unplaced = mutations.createCharacter(
                fixture.master.userId, fixture.gameId, "БезМеста-" + unique(""),
                null, null, null, null, true, false).id();

        assertThatThrownBy(() -> mutations.applyTransition(
                fixture.master.userId, fixture.gameId, GameTransition.MARK_READY, null))
                .as("играбельный персонаж без размещения блокирует готовность (§20)")
                .hasMessageContaining("нигде не размещён");

        mutations.placeCharacter(fixture.master.userId, fixture.gameId, unplaced, fixture.place);

        assertThat(mutations.applyTransition(
                fixture.master.userId, fixture.gameId, GameTransition.MARK_READY, null).state().readiness())
                .isEqualTo(Readiness.READY);
    }

    private boolean pausedOf(UUID gameId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT paused FROM game WHERE id = ?", Boolean.class, gameId));
    }

    private boolean archivedOf(UUID gameId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT archived FROM game WHERE id = ?", Boolean.class, gameId));
    }
}
