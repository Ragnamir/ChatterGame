package com.chattergame.message;

import com.chattergame.character.Character;
import com.chattergame.character.CharacterRepository;
import com.chattergame.chat.Chat;
import com.chattergame.chat.ChatRepository;
import com.chattergame.game.Game;
import com.chattergame.game.GameQueryService;
import com.chattergame.game.Membership;
import com.chattergame.game.MembershipRepository;
import com.chattergame.game.MembershipRole;
import com.chattergame.util.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Чтение ленты (`PLAN` E08, задачи 3 и 5). Read-путь один для всех ролей (`D-086`): строка
 * берётся из `inbox_entry`, а не отбирается по условиям на чтении, — иначе правило видимости
 * разъехалось бы по всем запросам и дало утечку при первом же изменении условий (`D-030`).
 *
 * <p>Субъект читающего выбирается здесь: игрок читает персонажем, наблюдатель — участием,
 * у которого персонажа нет вовсе (`D-063`). Разница только в том, какой субъект подставлен;
 * сам запрос ленты один и тот же.
 *
 * <p>Чужое участие проверяется по занятости персонажа (`D-100`): читать ленту персонажа
 * можно только смотря его глазами, иначе игрок вычитал бы знание соседа.
 */
@Service
public class MessageQueryService {

    /** Верхняя граница страницы ленты: без неё клиент выкачивает весь чат одним запросом. */
    public static final int MAX_PAGE = 200;

    private final MessageRepository messages;
    private final InboxRepository inbox;
    private final ReadCursorRepository cursors;
    private final ChatRepository chats;
    private final CharacterRepository characters;
    private final MembershipRepository memberships;
    private final GameQueryService games;

    public MessageQueryService(
            MessageRepository messages,
            InboxRepository inbox,
            ReadCursorRepository cursors,
            ChatRepository chats,
            CharacterRepository characters,
            MembershipRepository memberships,
            GameQueryService games) {
        this.messages = messages;
        this.inbox = inbox;
        this.cursors = cursors;
        this.chats = chats;
        this.characters = characters;
        this.memberships = memberships;
        this.games = games;
    }

    /**
     * Лента чата: сообщения и системные проекции одним порядком (`D-031`), продолжение
     * от `afterSeq`. Возвращается пара «строки + метка»: клиент держит метку как курсор,
     * и отдельного запроса «а где я» ему не нужно.
     */
    @Transactional(readOnly = true)
    public FeedPage feed(UUID userId, UUID gameId, UUID chatId, UUID asCharacter, long afterSeq, int limit) {
        Game game = games.requireReadable(userId, gameId);
        Chat chat = requireChat(gameId, chatId);
        InboxSubject subject = subjectFor(game, userId, asCharacter);
        int size = limit <= 0 ? MAX_PAGE : Math.min(limit, MAX_PAGE);
        List<FeedItem> items = messages.feed(gameId, subject, chatId, Math.max(afterSeq, 0L), size);
        long lastSeq = items.isEmpty()
                ? Math.max(afterSeq, 0L)
                : items.get(items.size() - 1).seq();
        return new FeedPage(items, lastSeq, cursors.lastSeq(subject, chatId), chat.kind().name());
    }

    /**
     * Вход в локацию (`D-032`): видно только последнее сообщение, остальное игрок
     * спрашивает у мастера. Курсор ставится на текущий максимум, то есть всё, что было
     * до входа, читается прочитанным, а не вываливается простыней.
     */
    @Transactional(readOnly = true)
    public FeedItem entryMessage(UUID userId, UUID gameId, UUID chatId, UUID asCharacter) {
        Game game = games.requireReadable(userId, gameId);
        requireChat(gameId, chatId);
        InboxSubject subject = subjectFor(game, userId, asCharacter);
        return messages.lastFor(gameId, subject, chatId).orElse(null);
    }

    /** Счётчики непрочитанного по чатам, где субъект получал строки (`D-036`). */
    @Transactional(readOnly = true)
    public List<InboxRepository.Unread> unread(UUID userId, UUID gameId, UUID asCharacter) {
        Game game = games.requireReadable(userId, gameId);
        InboxSubject subject = subjectFor(game, userId, asCharacter);
        List<InboxRepository.Unread> rows = new ArrayList<>(inbox.unread(subject));
        rows.sort(Comparator.comparing(InboxRepository.Unread::chatId));
        return rows;
    }

    /**
     * Курсоры субъекта по всем чатам. Нужны resume (`D-083`): клиент присылает последний
     * подтверждённый номер, но и «сколько уже прочитано» лежит здесь готовым.
     */
    @Transactional(readOnly = true)
    public Map<String, Long> cursors(UUID userId, UUID gameId, UUID asCharacter) {
        Game game = games.requireReadable(userId, gameId);
        InboxSubject subject = subjectFor(game, userId, asCharacter);
        Map<String, Long> result = new LinkedHashMap<>();
        for (ReadCursor cursor : cursors.findBySubject(subject)) {
            result.put(cursor.chatId().toString(), cursor.lastSeq());
        }
        return result;
    }

    /**
     * Субъект читающего. Наблюдатель читает участием (`D-086`), игрок и мастер — персонажем;
     * занятость проверяется здесь, потому что `assertCanPost` закрывает запись, а не чтение.
     */
    private InboxSubject subjectFor(Game game, UUID userId, UUID asCharacter) {
        Membership membership = memberships.find(game.id(), userId)
                .orElseThrow(() -> ApiException.forbidden("not_a_member", "Нет доступа к этой игре"));
        if (membership.role() == MembershipRole.OBSERVER) {
            if (asCharacter != null) {
                throw ApiException.forbidden(
                        "observer_has_no_character", "У наблюдателя нет персонажа");
            }
            return InboxSubject.membership(membership.id());
        }
        if (asCharacter == null) {
            throw ApiException.badRequest(
                    "character_required", "Укажите персонажа, чьими глазами читаете");
        }
        Character character = characters.findById(asCharacter)
                .orElseThrow(() -> ApiException.notFound("character_not_found", "Персонаж не найден"));
        if (!character.gameId().equals(game.id())) {
            throw ApiException.notFound("character_not_found", "Персонаж не найден");
        }
        if (membership.role() != MembershipRole.MASTER
                && !membership.id().equals(character.occupantMembershipId())) {
            throw ApiException.forbidden("not_your_character", "Это чужой персонаж");
        }
        return InboxSubject.character(asCharacter);
    }

    private Chat requireChat(UUID gameId, UUID chatId) {
        Chat chat = chats.findById(chatId)
                .orElseThrow(() -> ApiException.notFound("chat_not_found", "Чат не найден"));
        if (!chat.gameId().equals(gameId)) {
            throw ApiException.notFound("chat_not_found", "Чат не найден");
        }
        return chat;
    }

    /** Страница ленты: строки и метки, которые клиент держит между запросами (`D-083`). */
    public record FeedPage(List<FeedItem> items, long lastSeq, long readSeq, String chatKind) {
    }
}
