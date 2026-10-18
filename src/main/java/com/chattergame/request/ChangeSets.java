package com.chattergame.request;

import com.chattergame.character.CharacterField;
import com.chattergame.item.ItemSize;
import com.chattergame.item.ItemType;
import com.chattergame.util.ApiException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Разбор и проверка change set до применения (`PLAN` E14, задача 7; `D-043`). Форма заявки
 * описана `contract/changeset.schema.json`; здесь она же переведена в исполняемые проверки,
 * потому что валидатор схемы — это ещё одна зависимость, а поля операций всё равно обязан
 * проверять тот, кто их применяет.
 *
 * <p>Невалидный payload — это `FAILED`, а не частичное применение (`D-043`): разбор идёт
 * целиком до первой мутации, и одна плохая операция отменяет заявку, а не половину правок.
 * Поэтому `parse` ничего не пишет и не бросает исключение на середине: клиент получает
 * адресную причину («операция 2: не хватает transitionId»), а не «invalid input».
 */
final class ChangeSets {

    /** Предел числа операций в заявке: предложение модели иначе приходит простынёй на сотни строк. */
    static final int MAX_OPERATIONS = 50;

    private ChangeSets() {
    }

    /** Операция после проверки формы: порядок, словарь и готовый payload. */
    record Planned(int ordinal, ChangeOperationType op, Map<String, Object> payload) {
    }

    /**
     * Разбор payload заявки. Возвращает операции по порядку применения либо бросает
     * {@code invalid_change_set} с номером и причиной — до записи чего-либо в мир.
     */
    static List<Planned> parse(Map<String, Object> payload) {
        if (payload == null) {
            throw invalid("payload заявки пуст");
        }
        Object raw = payload.get("operations");
        if (!(raw instanceof List<?> operations)) {
            throw invalid("payload обязан содержать список operations");
        }
        if (operations.isEmpty()) {
            throw invalid("заявка без операций ничего не меняет");
        }
        if (operations.size() > MAX_OPERATIONS) {
            throw invalid("в заявке больше " + MAX_OPERATIONS + " операций");
        }
        List<Planned> planned = new ArrayList<>();
        for (int index = 0; index < operations.size(); index++) {
            planned.add(parseOne(index, operations.get(index)));
        }
        return planned;
    }

    private static Planned parseOne(int index, Object rawOperation) {
        int ordinal = index + 1;
        if (!(rawOperation instanceof Map<?, ?> operation)) {
            throw invalid(ordinal, "операция обязана быть объектом с полями op и payload");
        }
        Object wireName = operation.get("op");
        if (!(wireName instanceof String name)) {
            throw invalid(ordinal, "не указан вид операции");
        }
        ChangeOperationType op = ChangeOperationType.byWireName(name)
                .orElseThrow(() -> invalid(ordinal, "неизвестная операция " + name));
        Object rawPayload = operation.get("payload");
        if (!(rawPayload instanceof Map<?, ?> body)) {
            throw invalid(ordinal, "у операции " + name + " нет payload");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) body;
        check(op, ordinal, payload);
        return new Planned(ordinal, op, payload);
    }

    /**
     * Проверка полей по виду операции. Словари берутся из домена (`CharacterField`,
     * `ItemType`, `ItemSize`), а не переписываются здесь: второй перечень значений
     * разошёлся бы с первым на первом же расширении.
     */
    private static void check(ChangeOperationType op, int ordinal, Map<String, Object> payload) {
        switch (op) {
            case UPDATE_CHARACTER_FIELD -> {
                requireUuid(payload, "characterId", ordinal);
                String field = requireString(payload, "field", ordinal);
                if (!CharacterField.wireNames().contains(field.strip())) {
                    throw invalid(ordinal, "правке доступны поля " + String.join(", ", CharacterField.wireNames()));
                }
                if (present(payload, "value")) {
                    requireString(payload, "value", ordinal);
                }
            }
            case CREATE_ITEM -> {
                requireUuid(payload, "storageId", ordinal);
                requireText(payload, "name", ordinal, 200);
                String size = requireString(payload, "size", ordinal);
                parseEnum(ItemSize.class, size, ordinal, "size");
                if (present(payload, "type")) {
                    parseEnum(ItemType.class, requireString(payload, "type", ordinal), ordinal, "type");
                }
                if (present(payload, "quantity")) {
                    long quantity = requireNumber(payload, "quantity", ordinal);
                    if (quantity < 1) {
                        throw invalid(ordinal, "количество не меньше единицы");
                    }
                }
            }
            case MOVE_CHARACTER -> {
                requireUuid(payload, "characterId", ordinal);
                requireUuid(payload, "transitionId", ordinal);
            }
            case POST_MASTER_MESSAGE -> {
                requireUuid(payload, "chatId", ordinal);
                requireText(payload, "body", ordinal, 4000);
            }
            case UPDATE_STORAGE_CONTENTS -> {
                requireUuid(payload, "storageId", ordinal);
                boolean any = false;
                if (present(payload, "title")) {
                    requireText(payload, "title", ordinal, 200);
                    any = true;
                }
                if (present(payload, "description")) {
                    requireString(payload, "description", ordinal);
                    any = true;
                }
                if (!any) {
                    throw invalid(ordinal, "не названо ни одного поля хранилища");
                }
            }
            case CREATE_NOTE -> {
                requireText(payload, "body", ordinal, 2000);
                if (present(payload, "targetKind")) {
                    String kind = requireString(payload, "targetKind", ordinal);
                    if (!kind.equals("CHARACTER") && !kind.equals("MEMBERSHIP")) {
                        throw invalid(ordinal, "targetKind — CHARACTER или MEMBERSHIP");
                    }
                }
                if (present(payload, "targetId")) {
                    requireUuid(payload, "targetId", ordinal);
                }
                if (present(payload, "keywords")) {
                    if (!(payload.get("keywords") instanceof List<?> keywords)) {
                        throw invalid(ordinal, "keywords обязан быть списком");
                    }
                    if (keywords.size() > 10) {
                        throw invalid(ordinal, "ключей на заметку не больше десяти");
                    }
                }
            }
            case POST_CHARACTER_MESSAGE -> {
                requireUuid(payload, "chatId", ordinal);
                requireUuid(payload, "characterId", ordinal);
                String kind = requireString(payload, "kind", ordinal);
                if (!kind.equals("SPEECH") && !kind.equals("ACTION")) {
                    throw invalid(ordinal, "реплика автопилота — SPEECH или ACTION");
                }
                requireText(payload, "body", ordinal, 4000);
            }
        }
    }

    /**
     * Поле названо и не равно `null`. Отличается от `containsKey` намеренно: схема
     * `changeset.schema.json` разрешает явный `null` там, где поле необязательно
     * (`title: [string, 'null']`), и трактовать `null` как «указано пустое» значило бы
     * требовать от клиента отсутствия ключа вместо его значения.
     */
    private static boolean present(Map<String, Object> payload, String key) {
        return payload.containsKey(key) && payload.get(key) != null;
    }

    static UUID uuidOf(Map<String, Object> payload, String key) {
        Object raw = payload.get(key);
        if (raw == null) {
            return null;
        }
        try {
            return raw instanceof UUID value ? value : UUID.fromString(raw.toString());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_change_set", key + " не является идентификатором");
        }
    }

    private static void requireUuid(Map<String, Object> payload, String key, int ordinal) {
        if (uuidOf(payload, key) == null) {
            throw invalid(ordinal, "не хватает " + key);
        }
    }

    private static void requireText(Map<String, Object> payload, String key, int ordinal, int max) {
        String value = requireString(payload, key, ordinal);
        String stripped = value.strip();
        if (stripped.isEmpty()) {
            throw invalid(ordinal, key + " не может быть пустым");
        }
        if (stripped.length() > max) {
            throw invalid(ordinal, key + " длиннее " + max + " знаков");
        }
    }

    private static String requireString(Map<String, Object> payload, String key, int ordinal) {
        Object raw = payload.get(key);
        if (raw instanceof String value) {
            return value;
        }
        throw invalid(ordinal, "не хватает " + key);
    }

    private static long requireNumber(Map<String, Object> payload, String key, int ordinal) {
        Object raw = payload.get(key);
        if (raw instanceof Number value) {
            return value.longValue();
        }
        throw invalid(ordinal, key + " обязан быть числом");
    }

    private static <E extends Enum<E>> E parseEnum(
            Class<E> type, String raw, int ordinal, String key) {
        try {
            return Enum.valueOf(type, raw.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw invalid(ordinal, "неизвестное значение " + key + ": " + raw);
        }
    }

    private static ApiException invalid(int ordinal, String reason) {
        return invalid("операция " + ordinal + ": " + reason);
    }

    private static ApiException invalid(String reason) {
        return ApiException.badRequest("invalid_change_set", reason);
    }
}
