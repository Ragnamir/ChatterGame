package com.chattergame.web.note;

/** Правка комментария сохранённого сообщения. Текст берётся из снимка и не меняется (`D-055`). */
public record CommentRequest(String comment) {
}
