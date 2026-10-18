package com.chattergame.message;

import com.chattergame.chat.Chat;
import com.chattergame.chat.ChatRepository;
import com.chattergame.character.PresenceRepository;
import com.chattergame.game.Game;
import com.chattergame.game.GameRepository;
import com.chattergame.game.Membership;
import com.chattergame.game.MembershipRepository;
import com.chattergame.game.MembershipRole;
import com.chattergame.journal.GameEvent;
import com.chattergame.journal.GameEventRepository;
import com.chattergame.journal.GameEventType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Досчёт видимости для вошедшего в середину игры (`D-086`).
 *
 * <p>Наблюдатель, вошедший в идущую игру, обязан увидеть открытую историю, а не пустые
 * комнаты: `REQUIREMENTS §6` называет материализацию строк `inbox_entry` с субъектом-участием
 * и требует backfill. Без него пришлось бы либо читать мимо `inbox_entry` (запрещено `D-030`),
 * либо показывать опоздавшему пустоту.
 *
 * <p>Работа идёт отдельной транзакцией от входа: она пишет историю всех открытых чатов, и
 * заставлять человека ждать её в запросе незачем. Признак `membership.backfill_pending`
 * ставится при входе и снимается по завершении, поэтому упавший узел оставит след и
 * следующий прогон досчитает (`D-086`).
 */
@Service
public class VisibilityBackfillService {

    private final MembershipRepository memberships;
    private final ChatRepository chats;
    private final PresenceRepository presence;
    private final GameEventRepository events;
    private final GameRepository games;
    private final InboxMaterializer inbox;

    public VisibilityBackfillService(
            MembershipRepository memberships,
            ChatRepository chats,
            PresenceRepository presence,
            GameEventRepository events,
            GameRepository games,
            InboxMaterializer inbox) {
        this.memberships = memberships;
        this.chats = chats;
        this.presence = presence;
        this.events = events;
        this.games = games;
        this.inbox = inbox;
    }

    /** Счётчики прогона: сколько участий досчитано и сколько строк материала записано. */
    public record Report(int memberships, int rows) {
    }

    @Transactional
    public Report backfillAll() {
        List<UUID> pending = memberships.findPendingBackfill();
        int rows = 0;
        for (UUID membershipId : pending) {
            rows += backfill(membershipId);
            memberships.setBackfillPending(membershipId, false);
        }
        return new Report(pending.size(), rows);
    }

    /** Досчёт одного участия: нужен тестам и ручному повтору по признаку. */
    @Transactional
    public Report backfillOne(UUID membershipId) {
        int rows = backfill(membershipId);
        memberships.setBackfillPending(membershipId, false);
        return new Report(1, rows);
    }

    private int backfill(UUID membershipId) {
        Membership membership = memberships.findById(membershipId).orElse(null);
        if (membership == null || !membership.joined()) {
            return 0;
        }
        Game game = games.findById(membership.gameId()).orElse(null);
        if (game == null) {
            return 0;
        }
        // Мастеру и игрокам досчёт не нужен: их видимость держится персонажем, а история
        // персонажа материализована при записи. Досчёт существует ровно для участия-наблюдателя.
        if (membership.role() != MembershipRole.OBSERVER) {
            return 0;
        }
        InboxSubject subject = InboxSubject.membership(membership.id());
        int rows = 0;
        for (Chat chat : chats.findByGame(game.id())) {
            if (!inbox.openToObserver(chat.kind(), chat.id())) {
                continue;
            }
            for (GameEvent event : events.byChat(chat.id())) {
                if (hiddenAuthor(event)) {
                    continue;
                }
                inbox.backfill(chat.id(), event.seq(), messageId(event), subject);
                rows++;
            }
        }
        return rows;
    }

    /**
     * Скрытая запись наблюдателю не адресуется (`D-007`): содержимое уходит автору и мастеру,
     * и досчёт обязан вести себя так же, как материализация при записи, а не «догонять» то,
     * что закрыто.
     */
    private static boolean hiddenAuthor(GameEvent event) {
        if (event.type() != GameEventType.MESSAGE_POSTED) {
            return false;
        }
        Map<String, Object> payload = event.payload();
        return payload != null && Boolean.TRUE.equals(payload.get("hiddenAuthor"));
    }

    /** Ссылка на строку `message`, если событие её несёт: у системной проекции её нет (`D-031`). */
    private static UUID messageId(GameEvent event) {
        Map<String, Object> payload = event.payload();
        Object value = payload == null ? null : payload.get("messageId");
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
