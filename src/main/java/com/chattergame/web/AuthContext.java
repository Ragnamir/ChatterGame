package com.chattergame.web;

import com.chattergame.user.AppUser;

import java.time.Instant;
import java.util.UUID;

/**
 * Пользователь, пришедший в текущем запросе. Собирается один раз на запрос и передаётся
 * в контроллеры аргументом: тогда у сервисов нет зависимости от HTTP, а у клиента — способа
 * подменить идентичность заголовком.
 *
 * <p>`sessionId` нужен, чтобы «выйти» и «выйти на всех устройствах» различались: первое
 * закрывает текущее устройство, второе — все (`D-096`).
 */
public record AuthContext(AppUser user, UUID sessionId, Instant sessionExpiresAt) {
}
