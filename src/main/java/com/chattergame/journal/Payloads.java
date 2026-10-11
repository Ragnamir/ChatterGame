package com.chattergame.journal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

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
}
