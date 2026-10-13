package com.chattergame.message;

import com.chattergame.chat.Chat;
import com.chattergame.chat.ChatRepository;
import com.chattergame.character.Character;
import com.chattergame.character.CharacterRepository;
import com.chattergame.character.Presence;
import com.chattergame.character.PresenceRepository;
import com.chattergame.game.Game;
import com.chattergame.game.GameRepository;
import com.chattergame.game.Membership;
import com.chattergame.game.MembershipRepository;
import com.chattergame.restriction.RestrictionKind;
import com.chattergame.restriction.RestrictionRepository;
import com.chattergame.util.ApiException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Сборка {@link MessageAccess.Posting}: всё, от чего зависит право записи, читается из базы
 * в момент записи (`D-065`). Отдельный класс, потому что эту же сборку должен уметь
 * выполнить будущий обработчик WebSocket, а не только HTTP-контроллер: `assertCanPost`
 * вызывается на каждое входящее событие, а не один раз при установке соединения.
 *
 * <p>Запреты собираются оба набора сразу: персонаж и участие проверяются раздельно
 * (`D-064`, `D-099`), и решать, какой из них относится к типу сообщения, — дело матрицы.
 */
@Component
public class MessagePostingFactory {

    private final GameRepository games;
    private final MembershipRepository memberships;
    private final ChatRepository chats;
    private final CharacterRepository characters;
    private final PresenceRepository presence;
    private final RestrictionRepository restrictions;

    public MessagePostingFactory(
            GameRepository games,
            MembershipRepository memberships,
            ChatRepository chats,
            CharacterRepository characters,
            PresenceRepository presence,
            RestrictionRepository restrictions) {
        this.games = games;
        this.memberships = memberships;
        this.chats = chats;
        this.characters = characters;
        this.presence = presence;
        this.restrictions = restrictions;
    }

    public MessageAccess.Posting posting(UUID userId, UUID gameId, UUID chatId, UUID characterId) {
        Game game = games.findById(gameId)
                .orElseThrow(() -> ApiException.notFound("game_not_found", "Игра не найдена"));
        Membership membership = memberships.find(gameId, userId)
                .orElseThrow(() -> ApiException.forbidden("not_a_member", "Нет доступа к этой игре"));
        Chat chat = chats.findById(chatId)
                .orElseThrow(() -> ApiException.notFound("chat_not_found", "Чат не найден"));
        if (!chat.gameId().equals(gameId)) {
            throw ApiException.notFound("chat_not_found", "Чат не найден");
        }

        UUID occupantMembershipId = null;
        boolean archived = false;
        boolean present = false;
        Set<RestrictionKind> onCharacter = Set.of();
        if (characterId != null) {
            Character character = characters.findById(characterId)
                    .orElseThrow(() -> ApiException.notFound("character_not_found", "Персонаж не найден"));
            if (!character.gameId().equals(gameId)) {
                throw ApiException.notFound("character_not_found", "Персонаж не найден");
            }
            occupantMembershipId = character.occupantMembershipId();
            archived = character.archived();
            present = presence.findActive(characterId)
                    .map(Presence::chatId)
                    .filter(chatId::equals)
                    .isPresent();
            onCharacter = restrictionKinds(gameId, InboxSubject.character(characterId));
        }

        return new MessageAccess.Posting(
                membership.role(),
                membership.joined(),
                membership.banned(),
                membership.id(),
                characterId,
                occupantMembershipId,
                archived,
                chat.kind(),
                chatId,
                game.finalChatId(),
                game.state().phase(),
                chat.archived(),
                present,
                onCharacter,
                restrictionKinds(gameId, InboxSubject.membership(membership.id())));
    }

    /** Не снятые и не истёкшие запреты субъекта на текущий момент: срок проверяется на чтении. */
    public Set<RestrictionKind> restrictionKinds(UUID gameId, InboxSubject subject) {
        LinkedHashSet<RestrictionKind> kinds = new LinkedHashSet<>();
        for (var restriction : restrictions.findNotRevoked(gameId, subject)) {
            if (restriction.activeAt(java.time.Instant.now())) {
                kinds.add(restriction.kind());
            }
        }
        return kinds;
    }
}
