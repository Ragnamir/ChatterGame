package com.chattergame.journal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Сборка и разбор `payload` события. Живёт отдельно, потому что сериализация нужна и на
 * записи (мутация), и на чтении (аудит, resume), а `ObjectMapper` — общий бин.
 *
 * <p>Ошибка сериализации — это сломанная мутация, а не ошибка клиента: молча записать
 * пустой payload значило бы потерять данные события, поэтому исключение не глотается.
 */
@Component
public class Payloads {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private static final TypeReference<List<UUID>> NOTE_IDS = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public Payloads(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String write(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Не удалось собрать payload события", e);
        }
    }

    /**
     * Пустой `jsonb` невозможен по схеме, но защита остаётся: разбор не должен падать
     * на строке, которую записала предыдущая версия кода, иначе аудит станет нечитаемым.
     */
    public Map<String, Object> read(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Не удалось разобрать payload события", e);
        }
    }

    /**
     * Список идентификаторов заметок в колонку `message.matched_note_ids` (`D-050`).
     * Массив, а не объект: у него нет полей, и `payload`-формат здесь был бы лишним слоем.
     * `null` и пустой список пишутся одинаково — пустым массивом, потому что «нет подсветок»
     * и «поле не заполнено» для читателя одно и то же.
     */
    public String writeNoteIds(java.util.List<UUID> noteIds) {
        try {
            return objectMapper.writeValueAsString(noteIds == null ? List.of() : noteIds);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Не удалось собрать matched_note_ids", e);
        }
    }

    /** Разбор `matched_note_ids`: список идентификаторов заметок, подсвечивающих сообщение. */
    public List<UUID> readNoteIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, NOTE_IDS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Не удалось разобрать matched_note_ids", e);
        }
    }
}
