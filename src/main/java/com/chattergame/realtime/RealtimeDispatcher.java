package com.chattergame.realtime;

import com.chattergame.character.Character;
import com.chattergame.character.CharacterRepository;
import com.chattergame.game.Game;
import com.chattergame.game.GameRepository;
import com.chattergame.game.Membership;
import com.chattergame.game.MembershipRepository;
import com.chattergame.game.MembershipRole;
import com.chattergame.journal.GameEvent;
import com.chattergame.journal.GameEventType;
import com.chattergame.message.FeedItem;
import com.chattergame.message.InboxSubject;
import com.chattergame.message.MessageKind;
import com.chattergame.message.SubjectKind;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Рассылка по живым сокетам (`PLAN` E09, задачи 2–4, 7).
 *
 * <p>Слушает {@link Fanout} и по ссылке читает событие из журнала: уведомление несёт только
 * идентификатор (`D-084`). Кому событие предназначено, решает не рассылка, а материал
 * `inbox_entry` (`D-030`): сокет лишь доставляет то, что уже адресовано субъекту.
 *
 * <p>Два уровня (`D-035`) разведены здесь: мета-событие уходит по подписке всегда, тело —
 * только если канал чата открыт. Так трафик мастера пропорционален числу чатов, а не
 * объёму переписки.
 *
 * <p>Смена прав (`D-065`) проверяется на каждое событие: подписка, потерявшая основание,
 * гасится, и клиент узнаёт об этом сообщением, а не молчанием.
 */
@Component
public class RealtimeDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RealtimeDispatcher.class);

    /** События, после которых подписки перепроверяются: права или состав участников изменились. */
    private static final Set<GameEventType> RIGHTS_CHANGES = Set.of(
            GameEventType.RESTRICTION_SET,
            GameEventType.RESTRICTION_REVOKED,
            GameEventType.CHARACTER_ARCHIVED,
            GameEventType.CHARACTER_LEFT,
            GameEventType.CHARACTER_ENTERED,
            GameEventType.MEMBERSHIP_BANNED,
            GameEventType.MEMBERSHIP_LEFT,
            GameEventType.GAME_FINISHED);

    private final Fanout fanout;
    private final RealtimeSessions sessions;
    private final RealtimeQueryService reads;
    private final RealtimeEnvelope envelope;
    private final MembershipRepository memberships;
    private final CharacterRepository characters;
    private final GameRepository games;

    private Fanout.Subscription subscription;

    public RealtimeDispatcher(
            Fanout fanout,
            RealtimeSessions sessions,
            RealtimeQueryService reads,
            RealtimeEnvelope envelope,
            MembershipRepository memberships,
            CharacterRepository characters,
            GameRepository games) {
        this.fanout = fanout;
        this.sessions = sessions;
        this.reads = reads;
        this.envelope = envelope;
        this.memberships = memberships;
        this.characters = characters;
        this.games = games;
    }

    @PostConstruct
    void start() {
        subscription = fanout.subscribe(this::onEvent);
    }

    @PreDestroy
    void stop() {
        if (subscription != null) {
            subscription.close();
        }
    }

    void onEvent(GameEventRef ref) {
        try {
            reads.event(ref.gameId(), ref.seq()).ifPresent(event -> dispatch(ref.gameId(), event));
        } catch (RuntimeException e) {
            log.warn("Событие {} не разослано: {}", ref, e.toString());
        }
    }

    private void dispatch(UUID gameId, GameEvent event) {
        UUID chatId = event.chatId();
        if (chatId != null) {
            deliver(gameId, chatId, event);
        }
        if (RIGHTS_CHANGES.contains(event.type())) {
            revalidate(gameId);
        }
    }

    /** Раздача одного события подписчикам чата: мета — всем, тело — открывшим канал. */
    private void deliver(UUID gameId, UUID chatId, GameEvent event) {
        List<InboxSubject> recipients = reads.recipients(chatId, event.seq());
        for (RealtimeSession session : sessions.forGame(gameId)) {
            RealtimeSession.Subscription sub = session.subscription(chatId);
            if (sub == null || !recipients.contains(sub.subject())) {
                continue;
            }
            if (session.firstDelivery("meta", chatId, event.seq())) {
                send(session, envelope.meta(
                        gameId, chatId, event.seq(), kindOf(event), event.type().name(),
                        reads.unread(chatId, sub.subject())));
            }
            if (sub.bodies()) {
                reads.item(gameId, chatId, sub.subject(), event.seq())
                        .ifPresent(item -> deliverBody(session, gameId, item));
            }
        }
    }

    /**
     * Доставка строки, уже прочитанной из материала: мета-событие и — если канал чата
     * открыт — тело. Общая точка для рассылки и resume, чтобы дедупликация по
     * `(chat_id, seq)` и разделение каналов жили в одном месте, а не в двух.
     *
     * @return доставлена ли хоть одна новая строка: повторный resume обязан вернуть
     *     «ничего нового», иначе счётчик подтверждал бы доставку, которой не было
     */
    boolean deliver(RealtimeSession session, UUID gameId, FeedItem item, String eventType) {
        RealtimeSession.Subscription sub = session.subscription(item.chatId());
        if (sub == null) {
            return false;
        }
        boolean metaSent = false;
        if (session.firstDelivery("meta", item.chatId(), item.seq())) {
            send(session, envelope.meta(
                    gameId, item.chatId(), item.seq(), item.kind().name(), eventType,
                    reads.unread(item.chatId(), sub.subject())));
            metaSent = true;
        }
        return deliverBody(session, gameId, item) || metaSent;
    }

    /** Тело уходит только в открытый канал чата и только один раз на `(chat_id, seq)`. */
    private boolean deliverBody(RealtimeSession session, UUID gameId, FeedItem item) {
        RealtimeSession.Subscription sub = session.subscription(item.chatId());
        if (sub == null || !sub.bodies() || !session.firstDelivery("body", item.chatId(), item.seq())) {
            return false;
        }
        send(session, envelope.chat(gameId, item));
        return true;
    }

    /** Тип строки для мета-канала: у проекции журнала это `SYSTEM` (`D-031`). */
    private String kindOf(GameEvent event) {
        Object kind = event.payload() == null ? null : event.payload().get("kind");
        return kind == null ? MessageKind.SYSTEM.name() : kind.toString();
    }

    /**
     * Пересылка подписок при смене прав (`D-065`): подписка, у которой пропало основание —
     * участие снято, персонаж заархивирован, — гасится, и об этом сообщается сокету.
     * Иначе клиент продолжал бы считать чат открытым, а записи молча отклонялись бы.
     */
    private void revalidate(UUID gameId) {
        Game game = games.findById(gameId).orElse(null);
        if (game == null) {
            return;
        }
        for (RealtimeSession session : sessions.forGame(gameId)) {
            Membership membership = memberships.find(gameId, session.userId()).orElse(null);
            for (RealtimeSession.Subscription sub : session.subscriptions()) {
                if (canStillRead(membership, sub.subject())) {
                    continue;
                }
                session.unsubscribe(sub.chatId());
                send(session, envelope.subscription(
                        gameId, sub.chatId(), "revoked", "access_changed"));
            }
        }
    }

    /** Читает ли субъект чат после смены прав: участие живо, персонаж не заархивирован. */
    private boolean canStillRead(Membership membership, InboxSubject subject) {
        if (membership == null || !membership.joined() || membership.banned()) {
            return false;
        }
        if (subject.kind() != SubjectKind.CHARACTER) {
            return true;
        }
        Character character = characters.findById(subject.id()).orElse(null);
        if (character == null || character.archived()) {
            return false;
        }
        return membership.role() == MembershipRole.MASTER
                || membership.id().equals(character.occupantMembershipId());
    }

    private void send(RealtimeSession session, String payload) {
        try {
            session.send(payload);
        } catch (IOException e) {
            log.debug("Сокет {} недоступен: {}", session.id(), e.toString());
        }
    }
}
