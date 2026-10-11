package com.chattergame.skeleton;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Критерий приёмки `E03`: «удаление игры невозможно» (`D-010`). Проверка идёт по исходникам,
 * а не по ответу API: отсутствие эндпоинта `DELETE` не доказывает, что удаление не появится
 * в сервисе или репозитории завтра.
 *
 * <p>Тест работает в паре с глобальным инвариантом `E01InvariantsTest`: там перечислены все
 * доменные таблицы, здесь проверяется, что игры и участия попадают под правило и что архив
 * остаётся единственным способом «убрать» игру.
 */
class E03DeletionTest {

    private static final Path MAIN_SOURCES = Paths.get("src/main/java");

    private static final Set<String> GAME_TABLES = Set.of("game", "membership", "game_event", "game_stream");

    @Test
    void gamesAndMembershipsAreNeverPhysicallyDeleted() throws IOException {
        List<String> findings = new ArrayList<>();
        for (Path file : SourceScanner.javaFiles(MAIN_SOURCES)) {
            for (SourceScanner.Finding finding : SourceRules.physicalDeletions(file, GAME_TABLES)) {
                findings.add(finding.toString());
            }
        }

        assertThat(findings)
                .as("удаление игры или участия запрещено навсегда (D-010): только archived с причиной")
                .isEmpty();
    }

    /** Репозитории игр и участия не должны даже объявлять метод удаления: его вызовут «потом». */
    @Test
    void gameRepositoriesExposeNoDeleteApi() throws IOException {
        for (String name : List.of("GameRepository.java", "MembershipRepository.java")) {
            Path file = MAIN_SOURCES.resolve("com/chattergame/game/" + name);
            assertThat(file).as("%s существует", name).exists();

            String source = java.nio.file.Files.readString(file);
            assertThat(source)
                    .as("в %s не должно быть метода удаления", name)
                    .doesNotContain("public void delete")
                    .doesNotContain("DELETE FROM");
        }
    }

    /** Архив — не удаление: игра остаётся читаемой участникам (`D-095`). */
    @Test
    void archivingFlagsTheGameInsteadOfRemovingIt() throws IOException {
        String repository = java.nio.file.Files.readString(
                MAIN_SOURCES.resolve("com/chattergame/game/GameRepository.java"));

        assertThat(repository)
                .as("архивация выставляет флаг и причину, а не удаляет строку")
                .contains("SET archived = true")
                .contains("archived_reason");
    }

    /** Детектор обязан срабатывать на игру: правило, которое никогда не срабатывает, мертво. */
    @Test
    void ruleDetectsGameDeletion() throws IOException {
        Path canary = java.nio.file.Files.createTempFile("E03Canary", ".java");
        try {
            java.nio.file.Files.writeString(canary, """
                    class Bad {
                        String sql = "DELETE FROM game WHERE id = ?";
                    }
                    """);

            assertThat(SourceRules.physicalDeletions(canary, GAME_TABLES)).hasSize(1);
        } finally {
            java.nio.file.Files.deleteIfExists(canary);
        }
    }
}
