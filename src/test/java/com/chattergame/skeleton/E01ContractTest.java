package com.chattergame.skeleton;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Контракты этапа: OpenAPI и схема событий сверяются со снапшотами. Изменение контракта
 * без обновления снапшота — это изменение публичного поведения, и оно обязано быть
 * замечено гейтом, а не клиентом.
 */
class E01ContractTest {

    @Test
    void openApiMatchesSnapshot() {
        assertThat(ContractFiles.openApi())
                .as("OpenAPI изменился: обновите src/test/resources/contract/openapi.snapshot.yaml")
                .isEqualTo(ContractFiles.openApiSnapshot());
    }

    @Test
    void eventSchemaMatchesSnapshot() {
        assertThat(ContractFiles.eventSchema())
                .as("схема событий изменилась: обновите "
                        + "src/test/resources/contract/events.schema.snapshot.json")
                .isEqualTo(ContractFiles.eventSchemaSnapshot());
    }

    @Test
    void eventSchemaFixesMessageAndEventKinds() {
        String schema = ContractFiles.eventSchema();

        assertThat(schema)
                .as("пять типов сообщений зафиксированы решением D-031")
                .contains("SPEECH", "ACTION", "JOKE", "MASTER", "SYSTEM");
        assertThat(schema)
                .as("мета-событие игрового канала не содержит текста и имени говорящего (D-035)")
                .contains("speakerName");
    }
}
