package com.chattergame.web.ops;

import com.chattergame.media.OrphanAssetCollector;
import com.chattergame.ops.OpsService;
import com.chattergame.util.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Служебный контур эксплуатации (`PLAN` O02, `D-147`).
 *
 * <p>Аутентификация — отдельный токен из окружения, а не сессия игрока: эксплуатация
 * обращается к приложению из скрипта и мониторинга, где cookie нет, а мастер игры не должен
 * получать доступ к числам всего узла. Пустой токен выключает маршруты целиком: прод без
 * настроенного токена не отдаёт служебные данные никому.
 *
 * <p>Сравнение токена — постоянное по времени (`MessageDigest.isEqual`): иначе по задержке
 * ответа его можно подобрать посимвольно.
 */
@RestController
@RequestMapping("/ops")
public class OpsController {

    private static final String HEADER = "X-Ops-Token";

    private final OpsService ops;
    private final byte[] token;

    public OpsController(OpsService ops, @Value("${chattergame.ops.token:}") String token) {
        this.ops = ops;
        this.token = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics(@RequestHeader(name = HEADER, required = false) String provided) {
        authorize(provided);
        return ops.metrics();
    }

    @PostMapping("/orphans/dry-run")
    public OrphanAssetCollector.OrphanReport orphanDryRun(
            @RequestHeader(name = HEADER, required = false) String provided) {
        authorize(provided);
        return ops.orphanDryRun();
    }

    private void authorize(String provided) {
        if (token.length == 0) {
            throw ApiException.notFound("ops_disabled", "Служебный контур не включён");
        }
        if (provided == null
                || !MessageDigest.isEqual(token, provided.getBytes(StandardCharsets.UTF_8))) {
            throw ApiException.unauthorized("ops_token_required", "Нужен служебный токен");
        }
    }
}
