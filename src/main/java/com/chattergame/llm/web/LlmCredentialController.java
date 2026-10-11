package com.chattergame.llm.web;

import com.chattergame.llm.LlmCredentialService;
import com.chattergame.llm.LlmCredentialView;
import com.chattergame.web.AuthContext;
import com.chattergame.web.AuthContextResolver;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Ключи LLM в профиле. Наружу отдаётся {@link LlmCredentialView}, в котором нет поля секрета:
 * маска — не «пустая строка вместо ключа», а отсутствие поля, поэтому значение не может
 * просочиться через сериализацию (`D-093`).
 *
 * <p>Ключ принимается только на создание. Получить его обратно нельзя даже с верным паролем:
 * клиенту это не нужно, а забытый пароль меньше, чем утёкший ключ.
 */
@RestController
@RequestMapping("/me/llm-credentials")
public class LlmCredentialController {

    private final LlmCredentialService credentials;

    public LlmCredentialController(LlmCredentialService credentials) {
        this.credentials = credentials;
    }

    @GetMapping
    public List<LlmCredentialView> list(AuthContext context) {
        return credentials.list(currentUserId(context));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LlmCredentialView create(
            @Valid @RequestBody CreateCredentialRequest request, AuthContext context) {
        return credentials.create(
                currentUserId(context),
                request.label(),
                request.baseUrl(),
                request.model(),
                request.secret());
    }

    @PostMapping("/{id}/activate")
    public LlmCredentialView activate(@PathVariable UUID id, AuthContext context) {
        return credentials.setActive(currentUserId(context), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, AuthContext context) {
        credentials.delete(currentUserId(context), id);
    }

    private static UUID currentUserId(AuthContext context) {
        return AuthContextResolver.require(context).user().id();
    }
}
