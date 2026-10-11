package com.chattergame.game;

/**
 * Ось «можно ли играть» (`D-003`). `NOT_STARTED → RUNNING` и `RUNNING → FINISHED`
 * необратимы, поэтому промежуточных значений здесь нет: любое третье состояние сделало бы
 * «необратимо» проверяемым только по коду, а не по значению.
 */
public enum Phase {
    NOT_STARTED,
    RUNNING,
    FINISHED
}
