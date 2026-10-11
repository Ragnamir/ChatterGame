package com.chattergame.journal;

/**
 * Типы событий журнала — словарь контракта, а не строка на месте вызова. Перечисление
 * обязано совпадать с `enum` в `contract/events.schema.json`; расхождение ловит тест
 * `E03EventVocabularyTest`, потому что строка в `INSERT` не проверяется компилятором,
 * а потребители журнала (рассылка, resume, экспорт) читают именно эти значения (`D-033`).
 *
 * <p>Типы, принадлежащие будущим этапам, объявлены здесь сразу: словарь фиксируется целиком,
 * иначе он расползается по коду и перестаёт быть контрактом. Их использование начинается
 * вместе со своим этапом — `MEMBERSHIP_*` относятся к приглашениям и занятию (`E13`).
 */
public enum GameEventType {

    GAME_CREATED,
    GAME_CONTENT_CHANGED,
    GAME_READINESS_CHANGED,
    GAME_STARTED,
    GAME_FINISHED,
    GAME_PAUSED,
    GAME_RESUMED,
    GAME_ARCHIVED,
    MEMBERSHIP_INVITED,
    MEMBERSHIP_JOINED,
    MEMBERSHIP_LEFT,
    MEMBERSHIP_BANNED,
    MESSAGE_POSTED,
    CHARACTER_ENTERED,
    CHARACTER_LEFT,
    CHARACTER_STEALTHED,
    CHARACTER_REVEALED,
    CHARACTER_APPEARED_LEFT,
    PLAYER_LEFT,
    CHAT_CREATED,
    TRANSITION_OPENED,
    TRANSITION_CLOSED,
    ITEM_MOVED,
    RESTRICTION_SET,
    RESTRICTION_REVOKED,
    TURN_OPENED,
    TURN_CLOSED
}
