package com.chattergame.web.message;

/**
 * Отметка «прочитано» (`D-032`). Пустой `seq` означает «до текущего конца чата»:
 * клиент, открывший чат целиком, не обязан знать номер последнего события.
 */
public record ReadRequest(Long seq) {
}
