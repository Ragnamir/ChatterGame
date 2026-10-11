package com.chattergame.web;

import com.chattergame.util.ApiException;
import com.chattergame.user.SessionCookie;
import com.chattergame.user.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.Optional;

/**
 * Подставляет {@link AuthContext} в аргумент контроллера. Аутентификация проверяется
 * на каждом запросе, а не один раз при открытии соединения: сокет и сессия живут часами,
 * и за это время аккаунт успевают удалить (`D-065` по духу — права проверяются на вход).
 *
 * <p>Резолвер не бросает исключение, а кладёт `null`: есть эндпоинты, доступные и без
 * входа, и они сами решают, обязателен ли пользователь.
 */
@Component
public class AuthContextResolver implements HandlerMethodArgumentResolver {

    private final SessionService sessions;

    public AuthContextResolver(SessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return AuthContext.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        return resolve(request).orElse(null);
    }

    Optional<AuthContext> resolve(HttpServletRequest request) {
        return sessions
                .resolve(readToken(request))
                .map(found -> new AuthContext(found.user(), found.sessionId(), found.expiresAt()));
    }

    public static String readToken(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (var cookie : request.getCookies()) {
            if (SessionCookie.NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    public static AuthContext require(AuthContext context) {
        if (context == null) {
            throw ApiException.unauthorized("not_authenticated", "Требуется вход");
        }
        return context;
    }
}
