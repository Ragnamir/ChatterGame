package com.chattergame.web.user;

import com.chattergame.user.AccountDeletionService;
import com.chattergame.user.AppUser;
import com.chattergame.user.EmailVerificationService;
import com.chattergame.user.PasswordResetService;
import com.chattergame.user.SessionCookie;
import com.chattergame.user.UserService;
import com.chattergame.web.AuthContext;
import com.chattergame.web.AuthContextResolver;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Текущий пользователь: профиль, смена пароля, сброс по ссылке, закрытие аккаунта.
 *
 * <p>Сброс пароля намеренно без аутентификации — ссылка приходит на почту, и именно
 * потерявшими доступ пользуются этим путём. Поэтому же ответ на запрос сброса одинаков
 * для существующего и несуществующего адреса (`D-079`).
 */
@RestController
@RequestMapping("/me")
public class MeController {

    private final UserService users;
    private final PasswordResetService passwordReset;
    private final EmailVerificationService verification;
    private final AccountDeletionService deletion;
    private final SessionCookie cookie;

    public MeController(
            UserService users,
            PasswordResetService passwordReset,
            EmailVerificationService verification,
            AccountDeletionService deletion,
            SessionCookie cookie) {
        this.users = users;
        this.passwordReset = passwordReset;
        this.verification = verification;
        this.deletion = deletion;
        this.cookie = cookie;
    }

    @GetMapping
    public UserProfile me(AuthContext context) {
        return UserProfile.of(users.require(AuthContextResolver.require(context).user().id()));
    }

    @PatchMapping
    public UserProfile update(@Valid @RequestBody UpdateProfileRequest request, AuthContext context) {
        AppUser user = users.updateProfile(
                AuthContextResolver.require(context).user().id(),
                request.displayName(),
                request.email(),
                request.theme());
        // Новый адрес подтверждается заново (`D-141`). Письмо уходит после коммита профиля.
        verification.sendIfUnverified(user.id());
        return UserProfile.of(user);
    }

    @PostMapping("/password")
    public ResponseEntity<Void> changePassword(
            @Valid @RequestBody ChangePasswordRequest request, AuthContext context) {
        users.changePassword(
                AuthContextResolver.require(context).user().id(),
                request.currentPassword(),
                request.newPassword());
        // Смена пароля обрывает все сессии, включая текущую: cookie снимается тем же ответом.
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookie.clear().toString())
                .build();
    }

    /** Запрос письма. Всегда 202: различить существующий адрес по ответу нельзя (`D-079`). */
    @PostMapping("/password/reset")
    public ResponseEntity<Void> requestPasswordReset(
            @Valid @RequestBody PasswordResetRequest request, jakarta.servlet.http.HttpServletRequest http) {
        passwordReset.requestReset(request.email(), http.getRemoteAddr());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/password/reset/confirm")
    public ResponseEntity<Void> confirmPasswordReset(
            @Valid @RequestBody ResetPasswordConfirmRequest request) {
        passwordReset.confirm(request.token(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

    /** Повторное письмо подтверждения. Лимит и одинаковый ответ — как у сброса (`D-079`). */
    @PostMapping("/email/verification")
    public ResponseEntity<Void> resendVerification(
            AuthContext context, jakarta.servlet.http.HttpServletRequest http) {
        verification.resend(
                AuthContextResolver.require(context).user().id(), http.getRemoteAddr());
        return ResponseEntity.accepted().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> deleteAccount(
            @Valid @RequestBody DeleteAccountRequest request, AuthContext context) {
        deletion.deleteAccount(AuthContextResolver.require(context).user().id(), request.password());
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookie.clear().toString())
                .build();
    }
}
