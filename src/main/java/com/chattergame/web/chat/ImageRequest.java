package com.chattergame.web.chat;

import java.util.UUID;

/**
 * Картинка хранилища или предмета. `assetId == null` — снятие (`D-077`, `D-106`):
 * удаляется ссылка, а не ассет. Один рекорд на две сущности: правило одно и то же,
 * а второй рекорд с тем же единственным полем дал бы две правды о форме запроса.
 */
public record ImageRequest(UUID assetId) {
}
