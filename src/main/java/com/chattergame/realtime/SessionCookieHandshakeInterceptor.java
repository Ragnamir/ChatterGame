package com.chattergame.realtime;

import com.chattergame.user.SessionCookie;
import com.chattergame.user.SessionService;
import com.chattergame.web.AuthContextResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * Аутентификация сокета по cookie-сессии (`D-073`) — рабочая реализация
 * {@link HandshakeInterceptor}.
 *
 * <p>Cookie `HttpOnly`: XSS не украдёт креденшалы и не откроет сокет от чужого имени,
 * а переподключение не платит отдельным round trip. Взамен обязательны две вещи, и обе
 * здесь: проверка `Origin` по allowlist и серверная сессия, умирающая вместе с разлогином.
 *
 * <p>Запасная реализация — {@link TicketHandshakeInterceptor}: одноразовый билет вместо
 * cookie. Она нужна нативной обёртке и OAuth с другим происхождением и выключена, пока
 * этих сценариев нет.
 */
@Component
public class SessionCookieHandshakeInterceptor implements HandshakeInterceptor {

    /** Атрибут сессии сокета: идентификатор пользователя, чья cookie прошла проверку. */
    public static final String USER_ID = "chattergame.userId";

    /** Атрибут сессии сокета: идентификатор сессии, чтобы разлогин гасил и сокет. */
    public static final String SESSION_ID = "chattergame.sessionId";

    private final SessionService sessions;
    private final OriginAllowlist origins;

    public SessionCookieHandshakeInterceptor(SessionService sessions, OriginAllowlist origins) {
        this.sessions = sessions;
        this.origins = origins;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler handler,
            Map<String, Object> attributes) {
        String origin = request.getHeaders().getOrigin();
        if (!origins.allows(origin)) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        return sessions
                .resolve(AuthContextResolver.readToken(servletRequest(request)))
                .map(found -> {
                    attributes.put(USER_ID, found.user().id());
                    attributes.put(SESSION_ID, found.sessionId());
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
        // подписки не нужны: сессия уже разрешена и в attributes
    }

    private static HttpServletRequest servletRequest(ServerHttpRequest request) {
        if (request instanceof ServletServerHttpRequest servlet) {
            return servlet.getServletRequest();
        }
        throw new IllegalStateException("Ожидался ServletServerHttpRequest");
    }

    /** Имя cookie оставлено здесь, чтобы реализация не завязывалась на детали сессии. */
    static String cookieName() {
        return SessionCookie.NAME;
    }
}
