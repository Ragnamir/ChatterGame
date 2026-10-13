package com.chattergame.web.message;

import com.chattergame.message.MessageKind;

import java.util.UUID;

/**
 * Сообщение в запросе (`REQUIREMENTS §11`). Тип обязателен: он решает, от чьего имени
 * идёт запись и какие проверки применяются, и подставлять его по умолчанию значило бы
 * угадывать за игрока.
 */
public record PostMessageRequest(
        MessageKind kind,
        UUID characterId,
        String body,
        boolean onBehalfOfMaster) {
}
