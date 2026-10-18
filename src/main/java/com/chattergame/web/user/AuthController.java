package com.chattergame.web.user;

import com.chattergame.user.AppUser;
import com.chattergame.user.AuthenticationService;
import com.chattergame.user.EmailVerificationService;
import com.chattergame.user.RegistrationService;
import com.chattergame.user.SessionCookie;
import com.chattergame.user.SessionService;
import com.chattergame.web.AuthContext;
import com.chattergame.web.AuthContextResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Вход, выход и регистрация. Токен живёт в `HttpOnly` cookie, а не в теле ответа: так
 * он недоступен скрипту на странице, и XSS не превращается в угон сессии.
 *
 * <p>Ответы не различают «нет такого логина» и «неверный пароль» — это один код ошибки
 * из {@link AuthenticationService}, и проверять их отдельно бессмысленно.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final RegistrationService registration;
    private final AuthenticationService authentication;
    private final SessionService sessions;
    private final SessionCookie cookie;
    private final EmailVerificationService verification;

    public AuthController(
            RegistrationService registration,
            AuthenticationService authentication,
            SessionService sessions,
            SessionCookie cookie,
            EmailVerificationService verification) {
        this.registration = registration;
        this.authentication = authentication;
        this.sessions = sessions;
        this.cookie = cookie;
        this.verification = verification;
    }

    @PostMapping("/register")
    public ResponseEntity<UserProfile> register(
            @Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
        AppUser user = registration.register(
                request.login(), request.password(), request.displayName(), request.email());
        // Письмо уходит после коммита регистрации: внешний вызов внутри транзакции запрещён
        // (`D-044`), а отказ доставки не должен отменять уже созданный аккаунт.
        verification.sendIfUnverified(user.id());
        SessionService.IssuedSession session = sessions.issue(user.id(), userAgent(http));
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.SET_COOKIE, cookie.issue(session.token()).toString())
                .body(UserProfile.of(user));
    }

    /**
     * Подтверждение адреса по ссылке из письма. Без аутентификации: ссылку открывают из
     * почтового клиента, где cookie может отсутствовать. Ответ одинаков для истёкшей,
     * использованной и чужой ссылки (`D-079`).
     */
    @PostMapping("/verify-email")
    public ResponseEntity<Void> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        verification.verify(request.token());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/login")
    public ResponseEntity<UserProfile> login(
            @Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        AuthenticationService.Authenticated authenticated =
                authentication.login(request.login(), request.password(), userAgent(http));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.issue(authenticated.sessionToken()).toString())
                .body(UserProfile.of(authenticated.user()));
    }

    /** Выход текущего устройства. Cookie чистится даже без живой сессии — идемпотентно. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest http) {
        sessions.revoke(AuthContextResolver.readToken(http));
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookie.clear().toString())
                .build();
    }

    /** «Выйти на всех устройствах» (`D-096`). Смена пароля делает то же самое неявно. */
    @PostMapping("/logout-all")
    public ResponseEntity<Void> logoutAll(AuthContext context) {
        AuthContext current = AuthContextResolver.require(context);
        sessions.revokeAll(current.user().id());
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookie.clear().toString())
                .build();
    }

    private static String userAgent(HttpServletRequest request) {
        return request.getHeader(HttpHeaders.USER_AGENT);
    }
}
