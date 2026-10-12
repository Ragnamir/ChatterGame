package com.chattergame.web.chat;

import com.chattergame.character.CharacterQueryService.VisibleCharacter;

import java.util.UUID;

/**
 * Карточка персонажа в ответе API. `privateHistory` приходит пустым постороннему: это и есть
 * материализованное правило видимости (`REQUIREMENTS §9`), а не отсутствие данных.
 *
 * <p>`hidden` заполнен только для мастера; игроку скрытый персонаж не приходит вовсе.
 * `mine` говорит клиенту, показывать ли ему собственные служебные действия.
 */
public record CharacterView(
        UUID id,
        UUID gameId,
        String name,
        UUID portraitAssetId,
        String appearance,
        String publicHistory,
        String privateHistory,
        boolean playable,
        boolean npc,
        boolean archived,
        String archivedReason,
        boolean hidden,
        boolean mine) {

    public static CharacterView of(VisibleCharacter character) {
        return new CharacterView(
                character.id(),
                character.gameId(),
                character.name(),
                character.portraitAssetId(),
                character.appearance(),
                character.publicHistory(),
                character.privateHistory(),
                character.playable(),
                character.npc(),
                character.archived(),
                character.archivedReason(),
                character.hidden(),
                character.mine());
    }
}
