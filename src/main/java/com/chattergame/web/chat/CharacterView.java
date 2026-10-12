package com.chattergame.web.chat;

import com.chattergame.character.Character;

import java.util.UUID;

/** Персонаж в ответе API. На `E04` — идентификатор, игра и имя (`D-103`). */
public record CharacterView(UUID id, UUID gameId, String name) {

    public static CharacterView of(Character character) {
        return new CharacterView(character.id(), character.gameId(), character.name());
    }
}
