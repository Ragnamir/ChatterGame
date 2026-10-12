package com.chattergame.skeleton;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Критерий приёмки `E05`: орфан-сборка удаляет ассеты, а больше — ничего (`D-010`, `D-104`).
 *
 * <p>Проверка идёт по исходникам: обещание «удаляется только бесхозная картинка» держится
 * не на аккуратности того, кто напишет следующий репозиторий, а на том, что `DELETE FROM
 * asset` существует ровно в одном методе одного класса. Исключение выдано узко и нарочно,
 * поэтому тест проверяет обе его границы — и файл, и таблицу.
 */
class E05DeletionTest {

    private static final Path MAIN_SOURCES = Paths.get("src/main/java");

    @TempDir
    Path tempDir;

    /**
     * Доменные строки не удаляются нигде; `DELETE FROM asset` в `AssetRepository` — это
     * исключение `D-104`, и оно уже вычтено правилом. Пустой список здесь означает и то,
     * что удаления нет нигде больше, и то, что единственный разрешённый оператор не разросся.
     */
    @Test
    void noDomainRowsAreDeletedApartFromTheNamedAssetPurge() throws IOException {
        List<String> findings = new ArrayList<>();
        for (Path file : SourceScanner.javaFiles(MAIN_SOURCES)) {
            for (SourceScanner.Finding finding : SourceRules.physicalDeletions(file, SourceRules.DOMAIN_TABLES)) {
                findings.add(finding.toString());
            }
        }

        assertThat(findings)
                .as("удаление доменных строк запрещено; исключение — только орфан-сборка asset (D-104)")
                .isEmpty();
    }

    /** Исключение обязано быть используемым: разрешение без оператора маскировало бы любой `DELETE`. */
    @Test
    void assetPurgeIsTheOnlyPlaceAndItIsActuallyUsed() throws IOException {
        List<String> purgeStatements = literals(SourceRules.ASSET_PURGE).stream()
                .filter(text -> text.toLowerCase(Locale.ROOT).contains("delete from asset"))
                .toList();

        assertThat(purgeStatements)
                .as("D-104 выдано под конкретный оператор: без него исключение надо отозвать")
                .hasSize(1);
    }

    /** Другая таблица в исключённом файле — уже нарушение: исключение не про файл, а про оператор. */
    @Test
    void purgeExceptionDoesNotCoverOtherTables() throws IOException {
        Path repositories = MAIN_SOURCES.resolve("com/chattergame/media/AssetRepository.java");
        Path elsewhere = tempDir.resolve("AnotherRepository.java");
        Files.writeString(elsewhere, """
                class AnotherRepository {
                    String sql = "DELETE FROM asset WHERE id = ?";
                }
                """);

        assertThat(SourceRules.physicalDeletions(elsewhere, SourceRules.DOMAIN_TABLES))
                .as("тот же оператор в другом файле остаётся нарушением")
                .hasSize(1);
        assertThat(SourceRules.physicalDeletions(repositories, SourceRules.DOMAIN_TABLES))
                .as("в исключённом файле `DELETE FROM asset` разрешён")
                .isEmpty();
    }

    /** Канарейка наоборот: правило живо и ловит удаление чужой доменной строки. */
    @Test
    void ruleStillDetectsDeletionOfAnotherDomainTable() throws IOException {
        Path canary = tempDir.resolve("Canary.java");
        Files.writeString(canary, """
                class Canary {
                    String sql = "DELETE FROM chat WHERE id = ?";
                }
                """);

        assertThat(SourceRules.physicalDeletions(canary, SourceRules.DOMAIN_TABLES))
                .as("исключение не должно гасить правило для остальных таблиц")
                .hasSize(1);
    }

    /**
     * Сборщик знает все ссылки на ассет, существующие в схеме (`D-106`): пропущенная колонка
     * превращает сборку мусора в удаление живых картинок, и заметить это будет уже поздно.
     */
    @Test
    void orphanCollectorKnowsEveryReferenceColumn() throws IOException {
        String sql = String.join(" ", literals(SourceRules.ASSET_PURGE));

        assertThat(sql)
                .as("ссылка из пользователя (аватар)")
                .contains("app_user")
                .contains("avatar_asset_id");
        assertThat(sql).as("ссылка из локации (картинка)").contains("chat").contains("image_asset_id");
        assertThat(sql)
                .as("ссылка из персонажа (портрет)")
                .contains("character")
                .contains("portrait_asset_id");
        assertThat(sql).as("ссылка из предмета").contains("item").contains("image_asset_id");
    }

    private static List<String> literals(Path file) throws IOException {
        assertThat(file).as("%s существует", file).exists();
        return SourceScanner.of(file).literals().stream().map(SourceScanner.Literal::text).toList();
    }
}
