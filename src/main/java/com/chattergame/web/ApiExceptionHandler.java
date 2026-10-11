package com.chattergame.web;

import com.chattergame.util.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * Ошибки API в формате `ProblemDetail`. Машинный код кладётся в свойство `code`:
 * фронтенд переводит его по ключу интернализации, поэтому текст сообщения не является
 * контрактом и может меняться без слома клиента (`D-081`).
 *
 * <p>Исключения наружу не отдаются: сообщение необработанного исключения может содержать
 * и SQL, и значение ключа LLM.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handle(ApiException exception) {
        return ResponseEntity.status(exception.status())
                .body(problem(exception.status(), exception.code(), exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException exception) {
        String detail = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("Некорректный запрос");
        return ResponseEntity.badRequest()
                .body(problem(HttpStatus.BAD_REQUEST, "validation_failed", detail));
    }

    /** Битое тело запроса — 400, а не 500: это ошибка клиента, и клиент должен о ней узнать. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadable(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest()
                .body(problem(HttpStatus.BAD_REQUEST, "malformed_body", "Тело запроса не разобрано"));
    }

    /** Нет вида «упало — и вернулось сообщение драйвера»: наружу уходит только 500 и код. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception exception) {
        log.error("Необработанная ошибка API", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(problem(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "internal_error",
                        "Внутренняя ошибка. Повторите позже."));
    }

    static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("https://chattergame.local/problems/" + code));
        problem.setProperty("code", code);
        return problem;
    }
}
