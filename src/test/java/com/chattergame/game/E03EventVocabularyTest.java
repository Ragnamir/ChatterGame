package com.chattergame.game;

import com.chattergame.journal.GameEventType;
import com.chattergame.skeleton.ContractFiles;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Словарь типов событий — часть контракта, а не соглашение между классами. Тест держит три
 * вещи вместе: перечисление в коде, `enum` в {@code contract/events.schema.json} и карту
 * `notification_preference.event_type`, которая читает те же значения.
 *
 * <p>Поводом стал реальный дефект: `GAME_ARCHIVED` писался в журнал на `E02`, но
 * отсутствовал в замороженном перечислении, и обнаружилось это только при добавлении
 * соседних типов. Возврат к такому расхождению обязан валить гейт.
 */
class E03EventVocabularyTest {

    @Test
    void codeVocabularyMatchesFrozenSchema() throws Exception {
        JsonNode schema = new ObjectMapper().readTree(ContractFiles.eventSchema());
        JsonNode allowed = schema.at("/properties/eventType/enum");

        Set<String> inSchema = new LinkedHashSet<>();
        allowed.forEach(node -> inSchema.add(node.asText()));

        Set<String> inCode = new TreeSet<>();
        for (GameEventType type : GameEventType.values()) {
            inCode.add(type.name());
        }

        assertThat(inCode)
                .as("enum в contract/events.schema.json разошёлся с GameEventType")
                .containsExactlyInAnyOrderElementsOf(inSchema);
    }

    @Test
    void archivingTypeIsPartOfTheContract() {
        assertThat(GameEventType.valueOf("GAME_ARCHIVED"))
                .as("E02 уже пишет GAME_ARCHIVED: тип обязан быть в словаре")
                .isNotNull();
    }

    @Test
    void everyLifecycleTypeIsNamedExactlyOnce() {
        Set<String> names = new TreeSet<>();
        for (GameEventType type : GameEventType.values()) {
            assertThat(names.add(type.name())).as("дубль типа %s", type.name()).isTrue();
        }

        assertThat(names)
                .as("словарь жизненного цикла и участия зафиксирован целиком (D-102)")
                .contains(
                        "GAME_CREATED",
                        "GAME_CONTENT_CHANGED",
                        "GAME_READINESS_CHANGED",
                        "GAME_STARTED",
                        "GAME_FINISHED",
                        "GAME_PAUSED",
                        "GAME_RESUMED",
                        "GAME_ARCHIVED",
                        "MEMBERSHIP_INVITED",
                        "MEMBERSHIP_JOINED",
                        "MEMBERSHIP_LEFT",
                        "MEMBERSHIP_BANNED");
    }
}
