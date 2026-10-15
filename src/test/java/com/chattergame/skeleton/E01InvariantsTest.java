package com.chattergame.skeleton;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Глобальные инварианты (`PLAN §2`) как исполняемые правила. Проверки идут по
 * замаскированному исходнику: SQL берётся из литералов, а упоминания запретов
 * в комментариях и javadoc не считаются нарушениями.
 *
 * Отдельные тесты проверяют, что сами правила не выродились: детектор проверяется
 * на канарейке и на синтетических примерах, иначе «зелёный» гейт ничего не гарантирует.
 */
class E01InvariantsTest {

    @TempDir
    Path tempDir;

    private static final Path MAIN_SOURCES = Paths.get("src/main/java");
    private static final Path CANARY =
            Paths.get("src/test/resources/canary/CanaryPhysicalDeletion.java");

    private static List<String> scan(SourceCheck check) throws IOException {
        List<String> findings = new ArrayList<>();
        for (Path file : SourceScanner.javaFiles(MAIN_SOURCES)) {
            for (SourceScanner.Finding finding : check.in(file)) {
                findings.add(finding.toString());
            }
        }
        return findings;
    }

    @Test
    void noPhysicalDeletionOfDomainRows() throws IOException {
        List<String> findings = scan(
                file -> SourceRules.physicalDeletions(file, SourceRules.DOMAIN_TABLES));

        assertThat(findings)
                .as("физическое удаление доменных строк запрещено (D-010); "
                        + "единственное исключение — орфан-сборка asset (D-104)")
                .isEmpty();
    }

    @Test
    void journalHasNoUnboundedUpdates() throws IOException {
        List<String> findings = scan(file -> SourceRules.unboundedUpdates(file, "game_event"));

        assertThat(findings)
                .as("журнал append-only: UPDATE без WHERE переписал бы всю историю игры")
                .isEmpty();
    }

    @Test
    void messageIsNeverReadWithoutInboxEntry() throws IOException {
        List<String> findings = scan(SourceRules::messageReadsWithoutInbox);

        assertThat(findings)
                .as("чтение видимости только через inbox_entry (D-030)")
                .isEmpty();
    }

    @Test
    void noBlockingExternalCallsInsideTransactions() throws IOException {
        List<String> findings = scan(SourceRules::externalCallsInTransactions);

        assertThat(findings)
                .as("блокирующие внешние вызовы внутри @Transactional запрещены (D-044)")
                .isEmpty();
    }

    /**
     * Совпадение ключевых слов считается при записи и в досчёте, но не в read-пути
     * (`D-050`, `D-087`). Проверка парная: отрицательная — что в чтении совпадения нет,
     * положительная — что оно вообще где-то считается. Одной отрицательной мало: она
     * зелёная и на молчаливо удалённой подсветке, то есть пропускает ровно тот отказ,
     * который должна ловить (`E12`, критерий приёмки).
     */
    @Test
    void keywordMatchingNeverHappensInReadPath() throws IOException {
        List<Path> sources = SourceScanner.javaFiles(MAIN_SOURCES);

        assertThat(scan(SourceRules::keywordMatchingInReadPath))
                .as("в read-пути совпадение ключевых слов не вычисляется (D-050)")
                .isEmpty();
        assertThat(SourceRules.anyWritePathMatchesKeywords(sources))
                .as("подсветка обязана считаться в пути записи, иначе предыдущая проверка пуста")
                .isTrue();
    }

    @Test
    void ruleDetectsKeywordMatchingInAReadHandler() throws IOException {
        Path file = source("""
                class Bad {
                    @GetMapping("/notes")
                    List<String> read(String body) {
                        return List.of(KeywordMatcher.matches("ключ", false, body) ? "x" : "y");
                    }
                }
                """);

        assertThat(SourceRules.keywordMatchingInReadPath(file))
                .as("правило обязано краснеть на синтетическом чтении")
                .hasSize(1);
    }

    @Test
    void ruleAllowsKeywordMatchingInTheWritePath() throws IOException {
        Path file = source("""
                class Fine {
                    void write(String body) {
                        boolean hit = KeywordMatcher.matches("ключ", false, body);
                    }
                }
                """);

        assertThat(SourceRules.keywordMatchingInReadPath(file))
                .as("совпадение в пути записи — законно (D-050)")
                .isEmpty();
    }

    @Test
    void messageHandlersCheckPermissionsPerEvent() throws IOException {
        List<String> findings = scan(SourceRules::messageHandlersWithoutPermissionCheck);

        assertThat(findings)
                .as("права проверяются на каждое событие через assertCanPost (D-065)")
                .isEmpty();
    }

    @Test
    void worldMutationsGoThroughJournal() throws IOException {
        List<String> findings = scan(file -> SourceRules.worldMutationsWithoutJournal(file, SourceRules.DOMAIN_TABLES));

        assertThat(findings)
                .as("любая мутация мира оставляет событие в game_event (AGENTS §2, D-033)")
                .isEmpty();
    }

    /**
     * Проверка «на E01 веб-слоя нет» живёт в гейте как stage_checks, потому что её
     * истинность зависит от этапа: E02 вводит контроллеры, и общий тест стал бы красным
     * на своём же этапе. Тест следит за тем, чтобы правило не исчезло из гейта молча.
     */
    @Test
    void e01WebLayerRuleStillEnforcedByGate() throws IOException {
        String gate = Files.readString(Paths.get("scripts/gate.sh"));

        assertThat(gate)
                .as("E01 запрещает веб-слой; правило должно оставаться в scripts/gate.sh")
                .contains("E01 — веб-слой отсутствует")
                .contains("RestController|Controller|GetMapping|PostMapping|MessageMapping");
    }

    @Test
    void ruleDetectsUnboundedJournalUpdate() throws IOException {
        Path file = source("""
                class Sample {
                    String bad = "UPDATE game_event SET payload = '{}'";
                    String fine = "UPDATE game_event SET payload = '{}' WHERE id = ?";
                }
                """);

        List<SourceScanner.Finding> findings = SourceRules.unboundedUpdates(file, "game_event");

        assertThat(findings).hasSize(1);
        assertThat(findings.getFirst().text()).contains("SET payload");
        assertThat(findings.getFirst().text()).doesNotContain("WHERE");
    }

    @Test
    void ruleDetectsMutationWithoutJournalEvent() throws IOException {
        Path file = source("""
                @Service
                class Bad {
                    void rename() {
                        jdbc.update("UPDATE character SET name = ? WHERE id = ?", "x", 1);
                    }
                }
                """);

        List<SourceScanner.Finding> findings =
                SourceRules.worldMutationsWithoutJournal(file, SourceRules.DOMAIN_TABLES);

        assertThat(findings).hasSize(1);
        assertThat(findings.getFirst().text()).contains("UPDATE character");
    }

    @Test
    void ruleAcceptsMutationThatWritesJournalEvent() throws IOException {
        Path file = source("""
                @Service
                class Good {
                    void rename() {
                        jdbc.update("UPDATE character SET name = ? WHERE id = ?", "x", 1);
                        jdbc.update("INSERT INTO game_event (id, game_id, seq) VALUES (?, ?, ?)", 1, 2, 3);
                    }
                }
                """);

        assertThat(SourceRules.worldMutationsWithoutJournal(file, SourceRules.DOMAIN_TABLES)).isEmpty();
    }

    @Test
    void canaryPhysicalDeletionIsDetected() throws IOException {
        assertThat(SourceRules.physicalDeletions(CANARY, SourceRules.DOMAIN_TABLES))
                .as("детектор удаления обязан срабатывать на канарейке, иначе инвариант мёртв")
                .isNotEmpty();
    }

    @Test
    void canaryIsNotPartOfProductionSources() throws IOException {
        for (Path file : SourceScanner.javaFiles(MAIN_SOURCES)) {
            assertThat(file.toString())
                    .as("канарейка не должна попадать в продовые исходники")
                    .doesNotContain("CanaryPhysicalDeletion");
        }
    }

    @Test
    void ruleIgnoresProseAndComments() throws IOException {
        String source = """
                // DELETE FROM message — так делать нельзя, но это комментарий
                /* и здесь DELETE FROM message */
                class Sample {
                    String sql = "SELECT 1";
                }
                """;

        assertThat(SourceScanner.of(source).literals()).hasSize(1);
        assertThat(SourceScanner.of(source).literals().getFirst().text()).isEqualTo("SELECT 1");
    }

    @Test
    void ruleDetectsMessageReadWithoutInbox() throws IOException {
        Path file = source("""
                class Bad {
                    String sql = "SELECT * FROM message WHERE chat_id = ?";
                    String ok = "SELECT * FROM inbox_entry WHERE chat_id = ?";
                }
                """);

        List<SourceScanner.Finding> findings = SourceRules.messageReadsWithoutInbox(file);

        assertThat(findings).hasSize(1);
        assertThat(findings.getFirst().text()).contains("FROM message");
    }

    /**
     * Правила не должны перекрывать друг друга: `DELETE FROM message` — нарушение запрета
     * удаления, а не обхода видимости. Иначе провал указывает на чтение, которого нет, и
     * разработчик тратит время не там.
     */
    @Test
    void deletionIsNotReportedAsInboxBypass() throws IOException {
        Path file = source("""
                class Bad {
                    String sql = "DELETE FROM message WHERE chat_id = ?";
                }
                """);

        assertThat(SourceRules.physicalDeletions(file, SourceRules.DOMAIN_TABLES)).hasSize(1);
        assertThat(SourceRules.messageReadsWithoutInbox(file)).isEmpty();
    }

    @Test
    void ruleDetectsExternalCallInsideTransaction() throws IOException {
        Path file = source("""
                class Bad {
                    @Transactional
                    void mutate() {
                        restClient.post();
                    }

                    @Transactional
                    void fine() {
                        repository.save();
                    }
                }
                """);

        List<SourceScanner.Finding> findings = SourceRules.externalCallsInTransactions(file);

        assertThat(findings).hasSize(1);
        assertThat(findings.getFirst().text()).isEqualTo("RestClient");
    }

    /** Синтетический источник для проверок правил; каталог даёт JUnit, cleanup не нужен. */
    private Path source(String body) throws IOException {
        Path file = tempDir.resolve("Sample.java");
        Files.writeString(file, body);
        return file;
    }

    @FunctionalInterface
    private interface SourceCheck {
        List<SourceScanner.Finding> in(Path file) throws IOException;
    }
}
