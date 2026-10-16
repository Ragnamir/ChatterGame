package com.chattergame.request;

/**
 * Тип заявки (`REQUIREMENTS §17`). `CHARACTER_EDIT`, `ROLE_REQUEST` и `SLOT_QUESTION` —
 * объём `E14`; `CO_MASTER` и `AUTOPILOT` адресуют вызов модели и появятся вместе с
 * генератором payload, потому что без него заявка этих типов пуста.
 *
 * <p>Тип `ROLE_REQUEST` — просьба о персонаже под концепцию, `SLOT_QUESTION` — вопрос о слоте:
 * обе не меняют мир и применяются как ответ мастера, поэтому их применение — смена статуса,
 * а не набор операций.
 */
public enum RequestType {
    CHARACTER_EDIT,
    ROLE_REQUEST,
    SLOT_QUESTION,
    CO_MASTER,
    AUTOPILOT
}
