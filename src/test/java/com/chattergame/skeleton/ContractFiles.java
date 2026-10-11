package com.chattergame.skeleton;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

/** Чтение зафиксированных контрактов из classpath. */
final class ContractFiles {

    private ContractFiles() {
    }

    static Set<String> expectedTables() {
        Set<String> tables = new LinkedHashSet<>();
        for (String line : read("/contract/expected-tables.txt").lines().toList()) {
            if (!line.isBlank()) {
                tables.add(line.strip());
            }
        }
        return tables;
    }

    static String openApi() {
        return read("/openapi/openapi.yaml");
    }

    static String openApiSnapshot() {
        return read("/contract/openapi.snapshot.yaml");
    }

    static String eventSchema() {
        return read("/contract/events.schema.json");
    }

    static String eventSchemaSnapshot() {
        return read("/contract/events.schema.snapshot.json");
    }

    private static String read(String resource) {
        try (InputStream stream = ContractFiles.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Ресурс не найден: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
