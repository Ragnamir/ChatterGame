package com.chattergame.request;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * «Пожелания по отыгрышу» при занятии персонажа (`PLAN` E14, задача 5; `REQUIREMENTS §17`,
 * `D-139`).
 *
 * <p>Проверяются четыре свойства, которые нельзя увидеть из одной строки кода: текст живёт
 * на участии, а не на карточке; он виден мастеру и не виден другим участникам; `null` и
 * пустая строка значат разное; предел длины держит сервис, а не только колонка.
 */
class E14OccupationTest extends E14TestSupport {

    private MvcResult occupy(Fixture fixture, Account account, String wishes) throws Exception {
        Map<String, Object> body = wishes == null ? Map.of() : Map.of("roleplayWishes", wishes);
        return call(
                put("/games/" + fixture.gameId + "/characters/" + fixture.characterId + "/occupation"),
                account.token,
                body);
    }

    @Test
    void wishesAreStoredOnTheMembershipNotOnTheCard() throws Exception {
        Fixture fixture = fixture();

        MvcResult occupied = occupy(fixture, fixture.player, "Хочу мрачного рыцаря с прошлым");

        assertThat(occupied.getResponse().getStatus()).isEqualTo(200);
        assertThat(fixture.roleplayWishesOf(fixture.player.userId))
                .as("пожелание — текст игрока, он живёт на участии, а не в каноне карточки (D-139)")
                .isEqualTo("Хочу мрачного рыцаря с прошлым");
        assertThat(countRows(
                        "SELECT count(*) FROM information_schema.columns "
                                + "WHERE table_name = 'character' AND column_name = 'roleplay_wishes'"))
                .as("на карточке персонажа такого поля нет и быть не должно")
                .isZero();
    }

    @Test
    void masterSeesWishesInTheComposition() throws Exception {
        Fixture fixture = fixture();
        occupy(fixture, fixture.player, "Хочу играть разведчицу");

        MvcResult composition =
                call(get("/games/" + fixture.gameId + "/memberships"), fixture.master.token, null);

        JsonNode player = row(bodyOf(composition), fixture.player.userId);
        assertThat(player.path("roleplayWishes").asText())
                .as("мастер читает пожелание при распределении ролей (REQUIREMENTS §17)")
                .isEqualTo("Хочу играть разведчицу");
    }

    @Test
    void anotherParticipantDoesNotSeeTheWishes() throws Exception {
        Fixture fixture = fixture();
        occupy(fixture, fixture.player, "Хочу играть разведчицу");
        Account other = account("СмотрящийРядом");
        mutations.joinOpenGame(other.userId, fixture.gameId);

        MvcResult composition =
                call(get("/games/" + fixture.gameId + "/memberships"), fixture.player.token, null);

        JsonNode mine = json.readTree(bodyOf(composition));
        assertThat(mine)
                .as("не-мастеру состав приходит одной строкой — своей: чужого текста в ответе нет")
                .hasSize(1);
        assertThat(mine.get(0).path("userId").asText()).isEqualTo(fixture.player.userId.toString());
        assertThat(fixture.roleplayWishesOf(other.userId))
                .as("свой текст у второго участника пуст: пожелание первого ему не досталось")
                .isNull();
    }

    @Test
    void nullKeepsTheTextAndEmptyStringClearsIt() throws Exception {
        Fixture fixture = fixture();
        occupy(fixture, fixture.player, "Хочу мрачного рыцаря");
        occupy(fixture, fixture.player, null);

        assertThat(fixture.roleplayWishesOf(fixture.player.userId))
                .as("null значит «не указал вовсе»: написанное остаётся (D-139)")
                .isEqualTo("Хочу мрачного рыцаря");

        occupy(fixture, fixture.player, "");

        assertThat(fixture.roleplayWishesOf(fixture.player.userId))
                .as("пустая строка значит «стёр написанное»")
                .isNull();
    }

    @Test
    void wishesLongerThanTheLimitAreRefused() throws Exception {
        Fixture fixture = fixture();

        MvcResult refused = occupy(fixture, fixture.player, "я".repeat(501));

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(json.readTree(bodyOf(refused)).path("code").asText()).isEqualTo("wishes_too_long");
        assertThat(fixture.roleplayWishesOf(fixture.player.userId))
                .as("отказ ничего не записал")
                .isNull();
    }

    /** Строка состава по идентификатору пользователя: порядок строк контрактом не задан. */
    private JsonNode row(String body, java.util.UUID userId) throws Exception {
        for (JsonNode node : json.readTree(body)) {
            if (node.path("userId").asText().equals(userId.toString())) {
                return node;
            }
        }
        throw new AssertionError("В составе нет участия пользователя " + userId);
    }
}
