package com.chattergame.web.note;

import java.util.UUID;

/**
 * Запрос сохранения сообщения в тетрадь (`REQUIREMENTS §15.2`). Комментарий и ссылка на
 * заметку необязательны: запись ценна и сама по себе, снимком текста (`D-055`).
 */
public record SaveMessageRequest(UUID messageId, String comment, UUID noteId) {
}
