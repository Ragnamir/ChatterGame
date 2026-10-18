package com.chattergame.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Разбор ответа модели (`D-041`, `D-043`). Модель охотно заворачивает JSON в ```json и
 * добавляет пояснения до и после, поэтому ответ очищается, а не считается невалидным с
 * первого символа. Но всё, что осталось после очистки, обязано быть объектом: «невалидный
 * ответ» — это `FAILED`, а не попытка угадать намерение (`D-043`).
 */
@Component
public class LlmJson {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final ObjectMapper json;

    public LlmJson(ObjectMapper json) {
        this.json = json;
    }

    public Map<String, Object> object(String raw) {
        String text = raw == null ? "" : raw.strip();
        int fence = text.indexOf("```");
        if (fence >= 0) {
            int firstLine = text.indexOf('\n', fence);
            int end = text.indexOf("```", fence + 3);
            if (firstLine > 0 && end > firstLine) {
                text = text.substring(firstLine + 1, end).strip();
            }
        }
        int start = text.indexOf('{');
        int finish = text.lastIndexOf('}');
        if (start < 0 || finish <= start) {
            throw new LlmCallException(
                    LlmErrorKind.INVALID_RESPONSE, "В ответе модели нет JSON-объекта");
        }
        try {
            Map<String, Object> parsed = json.readValue(text.substring(start, finish + 1), MAP);
            return parsed == null ? new LinkedHashMap<>() : parsed;
        } catch (JsonProcessingException e) {
            throw new LlmCallException(
                    LlmErrorKind.INVALID_RESPONSE, "Ответ модели не разобран как JSON", e);
        }
    }
}
