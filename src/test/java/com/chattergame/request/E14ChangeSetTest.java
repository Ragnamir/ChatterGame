package com.chattergame.request;

import com.chattergame.character.CharacterField;
import com.chattergame.util.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Разбор и проверка change set (`PLAN` E14, задачи 2 и 7; `D-041`, `D-043`).
 *
 * <p>Проверки адресные: словари операций и полей карточки сравниваются с
 * `contract/changeset.schema.json`, потому что второй перечень значений расходится с
 * первым на первом же расширении, а заметить это можно только тестом. Плюс проверяется,
 * что невалидный payload отсекается <em>до</em> первой мутации: иначе «половина правок»
 * стала бы штатным исходом.
 */
class E14ChangeSetTest {

    private static final Path SCHEMA = Path.of("src/main/resources/contract/changeset.schema.json");

    @Test
    void operationVocabularyMatchesTheJsonSchema() throws Exception {
        JsonNode schema = new ObjectMapper().readTree(Files.readString(SCHEMA));
        Set<String> declared = new LinkedHashSet<>();
        for (JsonNode name : schema.at("/properties/operations/items/properties/op/enum")) {
            declared.add(name.asText());
        }

        Set<String> implemented = new LinkedHashSet<>();
        for (ChangeOperationType op : ChangeOperationType.values()) {
            implemented.add(op.wireName());
        }

        assertThat(implemented)
                .as("словарь операций обязан совпадать со схемой: иначе схема разрешает "
                        + "операцию, которой нет в коде, или наоборот")
                .containsExactlyInAnyOrderElementsOf(declared);
    }

    @Test
    void characterFieldVocabularyMatchesTheJsonSchema() throws Exception {
        JsonNode schema = new ObjectMapper().readTree(Files.readString(SCHEMA));
        Set<String> declared = new LinkedHashSet<>();
        for (JsonNode name : schema.at("/$defs/update_character_field/properties/field/enum")) {
            declared.add(name.asText());
        }

        assertThat(CharacterField.wireNames())
                .as("правке заявкой доступен закрытый список полей карточки (`D-136`)")
                .containsExactlyInAnyOrderElementsOf(declared);
    }

    @Test
    void everyOperationDeclaresItsReversibility() {
        for (ChangeOperationType op : ChangeOperationType.values()) {
            assertThat(op.wireName())
                    .as("имя операции — часть контракта, а не отладочная строка")
                    .isNotBlank();
        }
        assertThat(ChangeOperationType.CREATE_ITEM.reversible())
                .as("удаление предмета запрещено, поэтому создание необратимо (D-010)")
                .isFalse();
        assertThat(ChangeOperationType.POST_MASTER_MESSAGE.reversible())
                .as("отзыв сообщений запрещён, поэтому мастерское сообщение необратимо (D-008)")
                .isFalse();
        assertThat(ChangeOperationType.CREATE_NOTE.reversible())
                .as("заметка только заводит строку, откатывать нечего (D-055)")
                .isFalse();
        assertThat(ChangeOperationType.UPDATE_CHARACTER_FIELD.reversible()).isTrue();
        assertThat(ChangeOperationType.MOVE_CHARACTER.reversible()).isTrue();
        assertThat(ChangeOperationType.UPDATE_STORAGE_CONTENTS.reversible()).isTrue();
    }

    @Test
    void parseKeepsTheOrderOfOperations() {
        UUID characterId = UUID.randomUUID();
        UUID storageId = UUID.randomUUID();
        List<ChangeSets.Planned> planned = ChangeSets.parse(changeSet(
                field(characterId, "раз"),
                item(storageId, "Заявка-меч"),
                field(characterId, "два")));

        assertThat(planned).hasSize(3);
        assertThat(planned.get(0).ordinal()).isEqualTo(1);
        assertThat(planned.get(1).op()).isEqualTo(ChangeOperationType.CREATE_ITEM);
        assertThat(planned.get(2).ordinal()).isEqualTo(3);
        assertThat(planned.get(2).op()).isEqualTo(ChangeOperationType.UPDATE_CHARACTER_FIELD);
    }

    @Test
    void emptyChangeSetIsRejected() {
        assertThatThrownBy(() -> ChangeSets.parse(Map.of("operations", List.of())))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("без операций");
    }

    @Test
    void missingOperationsListIsRejected() {
        assertThatThrownBy(() -> ChangeSets.parse(Map.of("op", "update_character_field")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("operations");
    }

    @Test
    void tooManyOperationsAreRejected() {
        List<Map<String, Object>> operations = new ArrayList<>();
        for (int i = 0; i <= ChangeSets.MAX_OPERATIONS; i++) {
            operations.add(field(UUID.randomUUID(), "x"));
        }

        assertThatThrownBy(() -> ChangeSets.parse(Map.of("operations", operations)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("больше");
    }

    @Test
    void unknownOperationNamesTheOrdinal() {
        Map<String, Object> bad = new LinkedHashMapBuilder()
                .put("op", "delete_everything")
                .put("payload", Map.of())
                .build();

        assertThatThrownBy(() -> ChangeSets.parse(changeSet(bad)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("операция 1")
                .hasMessageContaining("delete_everything");
    }

    @Test
    void unknownCharacterFieldNamesTheOrdinalAndTheAllowedList() {
        UUID characterId = UUID.randomUUID();
        Map<String, Object> bad = new LinkedHashMapBuilder()
                .put("op", "update_character_field")
                .put("payload", Map.of(
                        "characterId", characterId.toString(),
                        "field", "playable",
                        "value", "true"))
                .build();

        assertThatThrownBy(() -> ChangeSets.parse(changeSet(bad)))
                .as("признак играбельности — структура карточки, а не текст (`D-136`)")
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("операция 1")
                .hasMessageContaining("appearance");
    }

    @Test
    void missingRequiredFieldNamesTheOperation() {
        Map<String, Object> bad = new LinkedHashMapBuilder()
                .put("op", "move_character")
                .put("payload", Map.of("characterId", UUID.randomUUID().toString()))
                .build();

        assertThatThrownBy(() -> ChangeSets.parse(changeSet(field(UUID.randomUUID(), "x"), bad)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("операция 2")
                .hasMessageContaining("transitionId");
    }

    @Test
    void zeroQuantityIsRejected() {
        Map<String, Object> bad = new LinkedHashMapBuilder()
                .put("op", "create_item")
                .put("payload", Map.of(
                        "storageId", UUID.randomUUID().toString(),
                        "name", "Заявка-меч",
                        "size", "SMALL",
                        "quantity", 0))
                .build();

        assertThatThrownBy(() -> ChangeSets.parse(changeSet(bad)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("количество");
    }

    @Test
    void unknownSizeIsRejected() {
        Map<String, Object> bad = new LinkedHashMapBuilder()
                .put("op", "create_item")
                .put("payload", Map.of(
                        "storageId", UUID.randomUUID().toString(),
                        "name", "Заявка-меч",
                        "size", "ОГРОМНЫЙ"))
                .build();

        assertThatThrownBy(() -> ChangeSets.parse(changeSet(bad)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("size");
    }

    @Test
    void namedButNullOptionalFieldIsAccepted() {
        UUID storageId = UUID.randomUUID();
        Map<String, Object> operation = new LinkedHashMapBuilder()
                .put("op", "update_storage_contents")
                .put("payload", new LinkedHashMapBuilder()
                        .put("storageId", storageId.toString())
                        .put("title", null)
                        .put("description", "новое описание")
                        .build())
                .build();

        assertThat(ChangeSets.parse(changeSet(operation)))
                .as("схема разрешает явный null у необязательного поля, и это не «поле названо»")
                .hasSize(1);
    }

    @SafeVarargs
    private static Map<String, Object> changeSet(Map<String, Object>... operations) {
        return Map.of("operations", List.of(operations));
    }

    private static Map<String, Object> field(UUID characterId, String value) {
        return new LinkedHashMapBuilder()
                .put("op", "update_character_field")
                .put("payload", new LinkedHashMapBuilder()
                        .put("characterId", characterId.toString())
                        .put("field", "appearance")
                        .put("value", value)
                        .build())
                .build();
    }

    private static Map<String, Object> item(UUID storageId, String name) {
        return new LinkedHashMapBuilder()
                .put("op", "create_item")
                .put("payload", new LinkedHashMapBuilder()
                        .put("storageId", storageId.toString())
                        .put("name", name)
                        .put("size", "SMALL")
                        .build())
                .build();
    }

    /** Небольшой строитель: `Map.of` не принимает `null`, а `null` здесь — часть контракта. */
    private static final class LinkedHashMapBuilder {
        private final java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();

        LinkedHashMapBuilder put(String key, Object value) {
            values.put(key, value);
            return this;
        }

        Map<String, Object> build() {
            return values;
        }
    }
}
