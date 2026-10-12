package com.chattergame.web.chat;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/**
 * Карточка персонажа в запросе мастера (`REQUIREMENTS §9`). Поля приходят все сразу: карточка
 * пишется целиком, потому что частичная правка расходится со снимком, который уже читают
 * клиенты, а `PATCH` с отсутствующим полем не отличить от «стереть поле».
 *
 * <p>`playable` и `npc` — технические признаки, их задаёт мастер: NPC никто не занимает,
 * но мастер пишет от его лица (`D-004`).
 */
public record CharacterRequest(
        @NotBlank(message = "Укажите имя персонажа")
        String name,
        UUID portraitAssetId,
        String appearance,
        String publicHistory,
        String privateHistory,
        boolean playable,
        boolean npc) {
}
