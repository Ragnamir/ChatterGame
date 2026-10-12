package com.chattergame.web.chat;

import java.util.UUID;

/**
 * Картинка локации. `assetId == null` — снятие: отдельного `DELETE` нет, потому что
 * удаляется не ассет, а ссылка на него (`D-077`, `D-106`).
 */
public record LocationImageRequest(UUID assetId) {
}
