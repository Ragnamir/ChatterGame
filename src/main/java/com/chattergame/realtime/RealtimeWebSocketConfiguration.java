package com.chattergame.realtime;

import jakarta.servlet.ServletContext;
import jakarta.websocket.WebSocketContainer;
import jakarta.websocket.server.ServerContainer;
import org.apache.tomcat.websocket.WsWebSocketContainer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Регистрация сокета (`PLAN` E09, задача 1). Путь включает игру: одна игра живёт целиком
 * на одном инстансе (`D-072`), поэтому `seq` в сокете — непрерывная последовательность.
 *
 * <p>Встроенная проверка `Origin` намеренно снята: Spring добавляет свой
 * {@code OriginHandshakeInterceptor} к каждой регистрации, и при пустом списке он требует
 * совпадения с источником запроса, то есть отвергает dev-клиент на другом порту. Точка
 * решения одна — {@link SessionCookieHandshakeInterceptor}: он идёт первым, ведётся
 * конфигурацией и умеет то, чего встроенный не выражает, — пустой список у него закрывает
 * доступ совсем, а не открывает его всем (`D-073`).
 */
@Configuration
@EnableWebSocket
@EnableScheduling
public class RealtimeWebSocketConfiguration implements WebSocketConfigurer {

    /** Заводской предел — 8 KB; реплика в 4000 знаков с конвертом в него не помещается. */
    private static final int MAX_MESSAGE_BYTES = 64 * 1024;

    private final RealtimeHandler handler;
    private final SessionCookieHandshakeInterceptor interceptor;

    public RealtimeWebSocketConfiguration(
            RealtimeHandler handler, SessionCookieHandshakeInterceptor interceptor) {
        this.handler = handler;
        this.interceptor = interceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/games/{gameId}/ws")
                .addInterceptors(interceptor)
                .setAllowedOriginPatterns("*");
    }

    /**
     * Предел буфера контейнера. Тело — до 4000 знаков (`D-098`), в UTF-8 и с конвертом
     * события это выходит за заводские 8 KB, и длинная реплика рвала бы соединение вместо
     * ответа ошибкой. Настройка применяется к контейнеру, если он есть: в тестах с `MockMvc`
     * сервер не поднимается, и отсутствие контейнера не должно ронять контекст — там
     * проверяется не размер буфера, а всё остальное.
     */
    @Bean
    WebSocketContainer webSocketContainer(ServletContext servletContext) {
        Object attribute = servletContext.getAttribute(ServerContainer.class.getName());
        if (attribute instanceof ServerContainer server) {
            server.setDefaultMaxTextMessageBufferSize(MAX_MESSAGE_BYTES);
            server.setDefaultMaxBinaryMessageBufferSize(MAX_MESSAGE_BYTES);
            return server;
        }
        return new WsWebSocketContainer();
    }
}
