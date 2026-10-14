package com.chattergame.realtime;

import com.chattergame.chat.Chat;
import com.chattergame.journal.GameEvent;
import com.chattergame.journal.GameEventRepository;
import com.chattergame.message.FeedItem;
import com.chattergame.message.InboxRepository;
import com.chattergame.message.InboxSubject;
import com.chattergame.message.MessageQueryService;
import com.chattergame.message.MessageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Чтение для сокета (`PLAN` E09, задачи 3–5).
 *
 * <p>Сокет не имеет собственного пути к данным: и тело, и видимость он берёт теми же
 * запросами, что HTTP-лента. Иначе реалтайм стал бы вторым read-путём, а `D-030` требует,
 * чтобы он был один — иначе первое же изменение условий видимости дало бы утечку в сокет.
 */
@Service
public class RealtimeQueryService {

    /** Верхняя граница resume: без неё клиент одним `last_seq = 0` выкачал бы игру целиком. */
    public static final int MAX_RESUME = 2000;

    private final GameEventRepository events;
    private final MessageRepository messages;
    private final InboxRepository inbox;
    private final MessageQueryService reads;

    public RealtimeQueryService(
            GameEventRepository events,
            MessageRepository messages,
            InboxRepository inbox,
            MessageQueryService reads) {
        this.events = events;
        this.messages = messages;
        this.inbox = inbox;
        this.reads = reads;
    }

    @Transactional(readOnly = true)
    public InboxSubject resolveSubject(UUID userId, UUID gameId, UUID asCharacter) {
        return reads.resolveSubject(userId, gameId, asCharacter);
    }

    /** Подписка на закрытый канал (`E11`): субъект и право участия решаются вместе. */
    @Transactional(readOnly = true)
    public InboxSubject resolveSubjectForChat(
            UUID userId, UUID gameId, UUID chatId, UUID asCharacter) {
        return reads.resolveSubjectForChat(userId, gameId, chatId, asCharacter);
    }

    @Transactional(readOnly = true)
    public Chat requireChat(UUID gameId, UUID chatId) {
        return reads.requireChat(gameId, chatId);
    }

    @Transactional(readOnly = true)
    public Optional<GameEvent> event(UUID gameId, long seq) {
        return events.find(gameId, seq);
    }

    /** Получал ли субъект строку этого события: рассылка адресуется материалу (`D-030`). */
    @Transactional(readOnly = true)
    public List<InboxSubject> recipients(UUID chatId, long seq) {
        return inbox.subjectsFor(chatId, seq);
    }

    @Transactional(readOnly = true)
    public long unread(UUID chatId, InboxSubject subject) {
        return inbox.unreadIn(subject, chatId);
    }

    /** Тело события для уже подписанного чата: строка читается из `inbox_entry` (`D-030`). */
    @Transactional(readOnly = true)
    public Optional<FeedItem> item(UUID gameId, UUID chatId, InboxSubject subject, long seq) {
        List<FeedItem> items = messages.feed(gameId, subject, chatId, seq - 1, 1);
        return items.stream().filter(item -> item.seq() == seq).findFirst();
    }

    /**
     * Resume (`D-083`): недостающее по всем подпискам одним запросом. Список подписок
     * превращается в одно условие `OR`, а не в цикл запросов: переподключение обязано
     * стоить один round trip к базе.
     */
    @Transactional(readOnly = true)
    public List<MessageRepository.FeedRow> resume(
            UUID gameId, List<RealtimeSession.Subscription> subscriptions, long lastSeq) {
        if (subscriptions.isEmpty()) {
            return List.of();
        }
        List<MessageRepository.ChatSubscription> pairs = subscriptions.stream()
                .map(subscription -> new MessageRepository.ChatSubscription(
                        subscription.chatId(), subscription.subject()))
                .toList();
        return messages.feedForResume(gameId, pairs, Math.max(lastSeq, 0L), MAX_RESUME);
    }
}
