package com.chattergame.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

/**
 * Провайдер `chat/completions` поверх JDK `HttpClient` (`D-092`). Отдельного SDK нет
 * намеренно: протокол один и стабильный, а вендор-специфичные возможности не обещаются.
 *
 * <p>Вызов синхронный, но выполняется на отдельном ограниченном пуле
 * ({@link LlmCallExecutor}), а не в транзакции: запрос к модели идёт десятки секунд и
 * выбрал бы пул Hikari до нуля (`D-044`).
 *
 * <p>Тело ошибки провайдера в исключение не попадает целиком: в нём бывает эхо запроса,
 * а запрос содержит тексты игры. В сообщение идёт только HTTP-статус.
 */
@Component
public class ChatCompletionsProvider implements LlmProvider {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    @Override
    public String complete(Request request) {
        String url = endpoint(request.baseUrl());
        ObjectNode body = JSON.createObjectNode();
        body.put("model", request.model());
        body.put("stream", false);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", request.system());
        messages.addObject().put("role", "user").put("content", request.user());

        HttpRequest httpRequest;
        try {
            httpRequest = HttpRequest.newBuilder(URI.create(url))
                    .timeout(request.timeout())
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + request.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                    .build();
        } catch (IllegalArgumentException e) {
            throw new LlmCallException(
                    LlmErrorKind.PROVIDER_ERROR, "Адрес провайдера не разобран", e);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new LlmCallException(
                    LlmErrorKind.PROVIDER_ERROR, "Запрос к провайдеру не собран", e);
        }

        HttpResponse<String> response;
        try {
            response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new LlmCallException(LlmErrorKind.TIMEOUT, "Провайдер не ответил вовремя", e);
        } catch (IOException e) {
            throw new LlmCallException(LlmErrorKind.PROVIDER_ERROR, "Провайдер недоступен", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmCallException(LlmErrorKind.TIMEOUT, "Вызов провайдера прерван", e);
        }

        int status = response.statusCode();
        if (status == 429) {
            throw new LlmCallException(
                    LlmErrorKind.RATE_LIMIT, "Провайдер ответил 429: лимит или квота");
        }
        if (status == 401 || status == 403) {
            throw new LlmCallException(
                    LlmErrorKind.AUTH_FAILED, "Провайдер отверг ключ: HTTP " + status);
        }
        if (status / 100 != 2) {
            throw new LlmCallException(
                    LlmErrorKind.PROVIDER_ERROR, "Провайдер ответил HTTP " + status);
        }
        return content(response.body());
    }

    /**
     * Адрес вызова. Мастер задаёт `base_url` вида `https://host/api/v1` (`D-092`), а
     * `chat/completions` дописывается здесь, чтобы ввод не зависел от того, помнит ли
     * человек хвост пути.
     */
    static String endpoint(String baseUrl) {
        String trimmed = baseUrl == null ? "" : baseUrl.strip();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.endsWith("/chat/completions")) {
            return trimmed;
        }
        return trimmed + "/chat/completions";
    }

    private static String content(String body) {
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode content = root.at("/choices/0/message/content");
            if (!content.isTextual() || content.asText().isBlank()) {
                throw new LlmCallException(
                        LlmErrorKind.INVALID_RESPONSE, "В ответе провайдера нет текста");
            }
            return content.asText();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new LlmCallException(
                    LlmErrorKind.INVALID_RESPONSE, "Ответ провайдера не разобран как JSON", e);
        }
    }
}
