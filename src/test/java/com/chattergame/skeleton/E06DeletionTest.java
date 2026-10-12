package com.chattergame.skeleton;

import com.chattergame.user.E02TestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Критерий приёмки `E06`: персонаж уходит из игры архивацией, а строки `character` и
 * `presence` не удаляются никогда (`D-009`, `D-010`).
 *
 * <p>Проверяются обе стороны обещания: в исходниках нет оператора удаления этих таблиц,
 * а в базе после архивации и перемещений строки остаются. Одного теста на источник мало —
 * правило можно соблюсти в тексте и нарушить вызовом, поэтому есть и запрос к живой базе.
 */
class E06DeletionTest extends E02TestSupport {

    private static final Path MAIN_SOURCES = Paths.get("src/main/java");

    @TempDir
    Path tempDir;

    /** Ни `character`, ни `presence` не удаляются нигде: правило `D-010` без исключений для них. */
    @Test
    void noDomainRowsOfCharactersAreDeleted() throws IOException {
        List<String> findings = new ArrayList<>();
        for (Path file : SourceScanner.javaFiles(MAIN_SOURCES)) {
            for (SourceScanner.Finding finding : SourceRules.physicalDeletions(file, SourceRules.DOMAIN_TABLES)) {
                findings.add(finding.toString());
            }
        }

        assertThat(findings)
                .as("единственное исключение из D-010 — орфан-сборка asset (D-104)")
                .isEmpty();
    }

    /** Правило живо и ловит именно эти таблицы: иначе «зелёный» ничего не гарантирует. */
    @Test
    void ruleDetectsDeletionOfCharacterAndPresence() throws IOException {
        Path canary = tempDir.resolve("CanaryCharacters.java");
        Files.writeString(canary, """
                class CanaryCharacters {
                    String gone = "DELETE FROM character WHERE id = ?";
                    String alsoGone = "DELETE FROM presence WHERE character_id = ?";
                }
                """);

        assertThat(SourceRules.physicalDeletions(canary, SourceRules.DOMAIN_TABLES))
                .as("правило обязано ловить обе таблицы этапа")
                .hasSize(2);
    }

    /** Обе таблицы этапа входят в перечень доменных: иначе удаление прошло бы мимо правила. */
    @Test
    void bothStageTablesAreDomainTables() {
        assertThat(SourceRules.DOMAIN_TABLES)
                .as("персонаж и присутствие — доменные строки")
                .contains("character", "presence");
    }

    /** Репозитории этапа не содержат ни одного оператора удаления. */
    @Test
    void characterRepositoriesContainNoDeletion() throws IOException {
        for (String name : List.of("character/CharacterRepository", "character/PresenceRepository")) {
            Path file = MAIN_SOURCES.resolve("com/chattergame/" + name + ".java");
            List<String> deleting = SourceScanner.of(file).literals().stream()
                    .map(SourceScanner.Literal::text)
                    .filter(text -> text.toUpperCase(Locale.ROOT).contains("DELETE FROM")
                            || text.toUpperCase(Locale.ROOT).contains("TRUNCATE"))
                    .toList();

            assertThat(deleting)
                    .as("%s не удаляет строки: архив и уход закрывают запись, а не стирают её", name)
                    .isEmpty();
        }
    }

    /** Живая база: после архивации и перемещений строки на месте, а DELETE отвечает отказом. */
    @Test
    void archivedAndMovedCharactersKeepTheirRows() throws Exception {
        String login = unique("Мастер");
        String token = registerAndGetToken(login, PASSWORD);
        MvcResult game = call(post("/games"), token, Map.of("title", "Игра " + unique("Д")));
        UUID gameId = UUID.fromString(json.readTree(bodyOf(game)).get("id").asText());
        UUID hall = location(token, gameId, "Зал");
        UUID corridor = location(token, gameId, "Коридор");
        MvcResult created = call(post("/games/" + gameId + "/characters"), token,
                Map.of("name", "Иван", "playable", true, "npc", false));
        UUID character = UUID.fromString(json.readTree(bodyOf(created)).get("id").asText());

        call(put("/games/" + gameId + "/characters/" + character + "/location"), token,
                Map.of("chatId", hall.toString()));
        call(put("/games/" + gameId + "/characters/" + character + "/location"), token,
                Map.of("chatId", corridor.toString()));
        MvcResult refused = call(
                delete("/games/" + gameId + "/characters/" + character), token, null);
        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        call(post("/games/" + gameId + "/characters/" + character + "/archive"), token,
                Map.of("reason", "уехал"));

        assertThat(countRows("SELECT count(*) FROM character WHERE id = ?", character)).isEqualTo(1);
        assertThat(countRows("SELECT count(*) FROM presence WHERE character_id = ?", character))
                .as("история присутствия сохраняется целиком: две локации — две строки")
                .isEqualTo(2);
        assertThat(countRows(
                        "SELECT count(*) FROM presence WHERE character_id = ? AND left_at IS NULL",
                        character))
                .as("после архивации активного присутствия нет")
                .isZero();
    }

    /**
     * Частичный индекс активного присутствия проверяется по определению в базе, а не по факту
     * «индекс существует». `createIndex` в Liquibase предикат `where` молча игнорирует: с ним
     * уникальный индекс получается полным и запрещает второе перемещение персонажа (`D-007`).
     * Миграция поэтому пишет индекс сырым `sql`, а тест держит предикат на месте.
     */
    @Test
    void activePresenceIndexIsPartialInTheDatabase() {
        String definition = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'ux_presence_active_character'",
                String.class);

        assertThat(definition)
                .as("без предиката история уходов становится невозможной")
                .containsIgnoringCase("WHERE")
                .containsIgnoringCase("left_at IS NULL");
    }

    private UUID location(String token, UUID gameId, String title) throws Exception {
        MvcResult created = call(post("/games/" + gameId + "/locations"), token,
                Map.of("title", title, "description", "описание " + title));
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        return UUID.fromString(json.readTree(bodyOf(created)).get("id").asText());
    }
}
