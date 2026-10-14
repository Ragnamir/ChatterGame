package com.chattergame.message;

import com.chattergame.chat.Chat;
import com.chattergame.chat.ChatMember;
import com.chattergame.chat.ChatKind;
import com.chattergame.chat.ChatMemberRepository;
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

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
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
    private final ChatMemberRepository members;

    public MessagePostingFactory(
            GameRepository games,
            MembershipRepository memberships,
            ChatRepository chats,
            CharacterRepository characters,
            PresenceRepository presence,
            RestrictionRepository restrictions,
            ChatMemberRepository members) {
        this.games = games;
        this.memberships = memberships;
        this.chats = chats;
        this.characters = characters;
        this.presence = presence;
        this.restrictions = restrictions;
        this.members = members;
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

        ChannelMembership channel = channelMembership(chat);
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
            onCharacter = restrictionKinds(gameId, InboxSubject.character(characterId));
        }
        // Присутствие — свойство локации; закрытые каналы держат участника в `chat_member`
        // (`D-123`), а личный чат с мастером — по участию, потому что персонажа в нём нет
        // вовсе (`D-037`).
        present = switch (chat.kind()) {
            case LOCATION -> characterId != null && presence.findActive(characterId)
                    .map(Presence::chatId)
                    .filter(chatId::equals)
                    .isPresent();
            case MASTER_DM -> channel.participation(membership.id());
            default -> characterId != null && channel.character(characterId);
        };

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
                chat.disabled(),
                present,
                characterId != null && channel.character(characterId),
                channel.participation(membership.id()),
                onCharacter,
                restrictionKinds(gameId, InboxSubject.membership(membership.id())));
    }

    /** Участники закрытого канала: персонажи и участия, ещё не вышедшие из чата (`D-123`). */
    private ChannelMembership channelMembership(Chat chat) {
        if (!chat.kind().privateChannel()) {
            return ChannelMembership.NONE;
        }
        UUID chatId = chat.id();
        Map<UUID, Character> occupied = characters.findByGame(chat.gameId()).stream()
                .filter(character -> character.occupantMembershipId() != null)
                .collect(java.util.stream.Collectors.toMap(
                        Character::id, java.util.function.Function.identity(), (first, second) -> first));
        Set<UUID> characterMembers = new HashSet<>();
        Set<UUID> membershipMembers = new HashSet<>();
        for (ChatMember member : members.findJoinedByChat(chatId)) {
            if (member.subject().kind() == SubjectKind.CHARACTER) {
                characterMembers.add(member.subject().id());
                Character character = occupied.get(member.subject().id());
                if (character != null) {
                    membershipMembers.add(character.occupantMembershipId());
                }
            } else {
                membershipMembers.add(member.subject().id());
            }
        }
        return new ChannelMembership(characterMembers, membershipMembers);
    }


    /** Участники закрытого канала: множества заведомо малые, поэтому обычные `HashSet`. */
    private record ChannelMembership(Set<UUID> characters, Set<UUID> memberships) {

        static final ChannelMembership NONE = new ChannelMembership(Set.of(), Set.of());

        boolean character(UUID characterId) {
            return characters.contains(characterId);
        }

        boolean participation(UUID membershipId) {
            return memberships.contains(membershipId);
        }
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
