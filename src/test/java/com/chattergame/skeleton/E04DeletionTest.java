package com.chattergame.skeleton;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Критерий приёмки `E04`: удаления локаций и переходов не существует (`D-010`, `D-103`).
 * Проверка идёт по исходникам, а не по ответу API: эндпоинт `DELETE`, отвечающий отказом
 * сегодня, ничего не говорит о том, что завтра в репозитории не появится `DELETE FROM chat`.
 *
 * <p>Тест парный к `E03DeletionTest` и опирается на тот же детектор `SourceRules`: правило
 * одно, а таблиц, на которые оно распространяется, становится больше с каждым этапом.
 */
class E04DeletionTest {

    private static final Path MAIN_SOURCES = Paths.get("src/main/java");

    private static final Set<String> GRAPH_TABLES =
            Set.of("chat", "transition", "transition_state");

    @Test
    void locationsAndTransitionsAreNeverPhysicallyDeleted() throws IOException {
        List<String> findings = new ArrayList<>();
        for (Path file : SourceScanner.javaFiles(MAIN_SOURCES)) {
            for (SourceScanner.Finding finding : SourceRules.physicalDeletions(file, GRAPH_TABLES)) {
                findings.add(finding.toString());
            }
        }

        assertThat(findings)
                .as("локация и переход архивируются с причиной, удаления нет (D-010, D-103)")
                .isEmpty();
    }

    /** Репозитории графа не должны даже объявлять метод удаления: его вызовут «потом». */
    @Test
    void graphRepositoriesExposeNoDeleteApi() throws IOException {
        for (String name : List.of("ChatRepository.java", "TransitionRepository.java")) {
            Path file = MAIN_SOURCES.resolve("com/chattergame/chat/" + name);
            assertThat(file).as("%s существует", name).exists();

            String source = Files.readString(file);
            assertThat(source)
                    .as("в %s не должно быть метода удаления", name)
                    .doesNotContain("public void delete")
                    .doesNotContain("DELETE FROM");
        }
    }

    /** Архив — не удаление: причина и момент остаются в строке (`D-009`). */
    @Test
    void archivingFlagsRowsInsteadOfRemovingThem() throws IOException {
        for (String name : List.of("ChatRepository.java", "TransitionRepository.java")) {
            String repository = Files.readString(
                    MAIN_SOURCES.resolve("com/chattergame/chat/" + name));

            assertThat(repository)
                    .as("%s выставляет флаг и причину, а не удаляет строку", name)
                    .contains("SET archived = true")
                    .contains("archived_reason");
        }
    }

    /**
     * Знание ребра тоже не удаляется: возврат к `UNKNOWN` пишется значением, иначе мастер,
     * забирая знание назад, стирал бы след и ломал инвариант `physicalDeletions`.
     */
    @Test
    void edgeStateReturnsToUnknownByValueNotByDeletion() throws IOException {
        String repository = Files.readString(
                MAIN_SOURCES.resolve("com/chattergame/chat/TransitionStateRepository.java"));

        assertThat(repository)
                .as("снятие знания — это upsert в UNKNOWN, а не DELETE (D-010)")
                .contains("ON CONFLICT")
                .doesNotContain("DELETE FROM");
        assertThat(Files.readString(MAIN_SOURCES.resolve("com/chattergame/chat/EdgeState.java")))
                .as("UNKNOWN хранится значением, поэтому состояние обязано быть в перечислении")
                .contains("UNKNOWN");
    }

    /** Детектор обязан срабатывать на локацию: правило, которое никогда не срабатывает, мертво. */
    @Test
    void ruleDetectsLocationDeletion() throws IOException {
        Path canary = Files.createTempFile("E04Canary", ".java");
        try {
            Files.writeString(canary, """
                    class Bad {
                        String sql = "DELETE FROM chat WHERE id = ?";
                    }
                    """);

            assertThat(SourceRules.physicalDeletions(canary, GRAPH_TABLES)).hasSize(1);
        } finally {
            Files.deleteIfExists(canary);
        }
    }
}
