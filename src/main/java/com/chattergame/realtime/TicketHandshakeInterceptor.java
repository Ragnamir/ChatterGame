package com.chattergame.realtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Запасная аутентификация сокета — одноразовый тикет (`D-073`). Выключена: включается
 * свойством `chattergame.realtime.ticket-auth=true`, когда появится нативная обёртка или
 * OAuth с другим происхождением, где cookie недоступна.
 *
 * <p>Тикет живёт 10–30 секунд и гасится при первом использовании. Это не «второй способ
 * входа»: он выдаётся уже аутентифицированным пользователем на обычном HTTP-запросе и
 * только переносит доказательство в сокет.
 */
@Component
@ConditionalOnProperty(prefix = "chattergame.realtime", name = "ticket-auth", havingValue = "true")
public class TicketHandshakeInterceptor implements HandshakeInterceptor {

    public static final String USER_ID = "chattergame.userId";
    public static final String SESSION_ID = "chattergame.sessionId";

    private final TicketStore tickets;
    private final OriginAllowlist origins;

    public TicketHandshakeInterceptor(TicketStore tickets, OriginAllowlist origins) {
        this.tickets = tickets;
        this.origins = origins;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler handler,
            Map<String, Object> attributes) {
        if (!origins.allows(request.getHeaders().getOrigin())) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        String ticket = queryParam(request, "ticket");
        return tickets.consume(ticket)
                .map(principal -> {
                    attributes.put(USER_ID, principal.userId());
                    attributes.put(SESSION_ID, principal.sessionId());
                    return true;
                })
                .orElseGet(() -> {
                    response.setStatusCode(HttpStatus.UNAUTHORIZED);
                    return false;
                });
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler handler,
            Exception exception) {
        // тикет уже погашен в beforeHandshake
    }

    private static String queryParam(ServerHttpRequest request, String name) {
        String query = request.getURI().getQuery();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && name.equals(pair.substring(0, equals))) {
                return pair.substring(equals + 1);
            }
        }
        return null;
    }

    /** Пользователь, которому выдан одноразовый тикет. */
    public record Principal(UUID userId, UUID sessionId) {
    }

    /** Хранилище одноразовых тикетов: интерфейс, потому что реализация зависит от хранилища. */
    public interface TicketStore {

        Optional<Principal> consume(String ticket);
    }

    /** Память процесса: тикет живёт секунды и не переживает перезапуск — этого достаточно. */
    public static class InMemoryTicketStore implements TicketStore {

        private final Map<String, Principal> issued = new ConcurrentHashMap<>();
        private final Map<String, Long> expiresAt = new ConcurrentHashMap<>();

        /** Выдача тикета на `ttlMillis`. Гасится при первом использовании либо по сроку. */
        public String issue(Principal principal, long ttlMillis) {
            String ticket = UUID.randomUUID().toString();
            issued.put(ticket, principal);
            expiresAt.put(ticket, System.currentTimeMillis() + ttlMillis);
            return ticket;
        }

        @Override
        public Optional<Principal> consume(String ticket) {
            if (ticket == null) {
                return Optional.empty();
            }
            Long expiry = expiresAt.remove(ticket);
            Principal principal = issued.remove(ticket);
            if (principal == null || expiry == null || expiry < System.currentTimeMillis()) {
                return Optional.empty();
            }
            return Optional.of(principal);
        }
    }
}
