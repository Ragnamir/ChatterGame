package com.chattergame.user;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Cookie сессии. `HttpOnly` — токен недоступен скрипту, `SameSite=Lax` — форма и переход
 * по ссылке работают, а межсайтовый POST с чужим cookie нет. `Secure` включается в `prod`:
 * в `dev` приложение живёт на `http`, и флаг сделал бы cookie неотправляемой.
 *
 * <p>Имя зафиксировано в `openapi.yaml`, потому что от него зависит клиент.
 */
@Component
public class SessionCookie {

    public static final String NAME = "CHATTERGAME_SESSION";

    private final boolean secure;

    public SessionCookie(@Value("${chattergame.session.cookie-secure:false}") boolean secure) {
        this.secure = secure;
    }

    public ResponseCookie issue(String token) {
        return base(token).maxAge(SessionService.LIFETIME).build();
    }

    /** При выходе cookie не удаляется мгновенно браузером, а обнуляется сроком жизни. */
    public ResponseCookie clear() {
        return base("").maxAge(Duration.ZERO).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/");
    }
}
