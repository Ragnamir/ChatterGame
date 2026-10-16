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
    MEMBERSHIP_DECLINED,
    MEMBERSHIP_LEFT,
    MEMBERSHIP_BANNED,
    MEMBERSHIP_UNBANNED,
    CHARACTER_OCCUPIED,
    CHARACTER_RELEASED,
    MESSAGE_POSTED,
    CHARACTER_CREATED,
    CHARACTER_CONTENT_CHANGED,
    CHARACTER_ARCHIVED,
    CHARACTER_ENTERED,
    CHARACTER_LEFT,
    CHARACTER_STEALTHED,
    CHARACTER_REVEALED,
    CHARACTER_APPEARED_LEFT,
    PLAYER_LEFT,
    CHAT_CREATED,
    CHAT_CONTENT_CHANGED,
    CHAT_ARCHIVED,
    CHAT_ACCESS_CHANGED,
    CHAT_MEMBER_INVITED,
    CHAT_MEMBER_JOINED,
    CHAT_MEMBER_DECLINED,
    CHAT_MEMBER_LEFT,
    TRANSITION_OPENED,
    TRANSITION_CLOSED,
    TRANSITION_ARCHIVED,
    TRANSITION_STATE_CHANGED,
    STORAGE_CREATED,
    STORAGE_CONTENT_CHANGED,
    STORAGE_ACCESS_CHANGED,
    ITEM_CREATED,
    ITEM_CONTENT_CHANGED,
    ITEM_MOVED,
    RESTRICTION_SET,
    RESTRICTION_REVOKED,
    TURN_OPENED,
    TURN_CLOSED,
    REQUEST_SUBMITTED,
    REQUEST_DECIDED,
    REQUEST_EXPIRED,
    REQUEST_ROLLED_BACK
}
