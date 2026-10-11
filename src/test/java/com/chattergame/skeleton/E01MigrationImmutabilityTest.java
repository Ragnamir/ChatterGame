package com.chattergame.skeleton;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Миграции только добавляются. Применённый changeset-файл не редактируется: базы, где он
 * уже выполнен, не увидят изменений. Гейт сверяет отпечатки файлов с манифестом, поэтому
 * правка старой миграции не проходит молча.
 */
class E01MigrationImmutabilityTest {

    private static final Path RESOURCES = Paths.get("src/main/resources");

    @Test
    void appliedChangesetsAreByteIdentical() throws IOException {
        List<String> drifted = new ArrayList<>();
        for (String[] entry : manifest()) {
            String expectedHash = entry[0];
            Path file = RESOURCES.resolve(entry[1]);
            assertThat(Files.exists(file))
                    .as("changeset из манифеста обязан существовать: %s", entry[1])
                    .isTrue();
            String actualHash = sha256(file);
            if (!expectedHash.equals(actualHash)) {
                drifted.add(entry[1] + " (ожидался " + expectedHash + ", получен " + actualHash + ")");
            }
        }

        assertThat(drifted)
                .as("применённые миграции нельзя править — только добавлять новые файлы")
                .isEmpty();
    }

    @Test
    void everyChangesetFileIsRegisteredInManifest() throws IOException {
        Path changesDir = RESOURCES.resolve("db/changelog/changes");
        List<String> registered = manifest().stream().map(entry -> entry[1]).toList();
        List<String> onDisk;
        try (var walk = Files.walk(changesDir)) {
            onDisk = walk.filter(path -> path.toString().endsWith(".yaml") || path.toString().endsWith(".sql"))
                    .map(path -> RESOURCES.relativize(path).toString())
                    .sorted()
                    .toList();
        }

        assertThat(onDisk)
                .as("новый changeset обязан попасть в манифест отпечатков")
                .isNotEmpty()
                .containsExactlyInAnyOrderElementsOf(registered);
    }

    @Test
    void changelogNamingFollowsConvention() throws IOException {
        Path changesDir = RESOURCES.resolve("db/changelog/changes");
        try (var walk = Files.walk(changesDir)) {
            List<String> files = walk.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .toList();

            assertThat(files).allSatisfy(name -> assertThat(name)
                    .as("файл миграции должен называться <NNN>-<stage>.yaml, получено: " + name)
                    .matches("\\d{3}-[a-z0-9-]+\\.yaml"));
        }
    }

    private static List<String[]> manifest() throws IOException {
        try (InputStream stream = E01MigrationImmutabilityTest.class
                .getResourceAsStream("/contract/changelog-manifest.txt")) {
            assertThat(stream).as("манифест отпечатков обязателен").isNotNull();
            String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return text.lines()
                    .map(String::strip)
                    .filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .map(line -> line.split("\\s{2,}", 2))
                    .filter(parts -> parts.length == 2)
                    .toList();
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
    }
}
