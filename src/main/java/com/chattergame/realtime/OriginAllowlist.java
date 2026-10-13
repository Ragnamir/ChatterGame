package com.chattergame.realtime;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Проверка `Origin` по allowlist (`D-073`). Без неё чужая страница в браузере пользователя
 * откроет сокет к серверу с его cookie: сокет не подчиняется SOP автоматически, как
 * обычный запрос, и браузер сам заголовок не проверяет. Пустой список означает «не
 * разрешено ниоткуда», а не «разрешено всем»: забытая конфигурация обязана закрывать
 * доступ, а не открывать его.
 */
@Component
public class OriginAllowlist {

    private final List<String> allowed;

    public OriginAllowlist(RealtimeProperties properties) {
        this.allowed = properties.getAllowedOrigins().stream()
                .map(origin -> origin.toLowerCase(Locale.ROOT))
                .toList();
    }

    /** Сравнение точное по схеме и хосту: подстрока `example.com` не пропускает `evil-example.com`. */
    public boolean allows(String origin) {
        if (origin == null || origin.isBlank()) {
            return false;
        }
        return allowed.contains(origin.strip().toLowerCase(Locale.ROOT));
    }
}
