package com.chattergame.game;

import com.chattergame.character.Character;
import com.chattergame.character.PresenceRepository;
import com.chattergame.message.MessageKind;
import com.chattergame.message.MessageQueryService;
import com.chattergame.message.MessageService;
import com.chattergame.message.VisibilityBackfillExecutor;
import com.chattergame.user.E02TestSupport;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

/**
 * Общая обвязка тестов `E13`: живая игра, аккаунты и пути участия через HTTP-слой.
 *
 * <p>Регистрация идёт через `MockMvc`, потому что приглашение адресуется логином
 * существующего аккаунта (`D-131`): тест обязан завести именно то, что увидит сервис, —
 * нормализованный логин и сессионную cookie. Игра создаётся сервисом: мастер всегда
 * один и тот же, и заводить его маршрутом значило бы проверять `E03` на каждом случае.
 *
 * <p>Локации две, персонажей два. Одной локации мало: признак «показывать место только
 * там, где уже был играбельный персонаж» (`D-129`) без второй локации не проверить —
 * открытой и закрытой не с чем сравнить.
 */
abstract class E13TestSupport extends E02TestSupport {

    @Autowired
    protected GameMutationService mutations;

    @Autowired
    protected MessageService writes;

    @Autowired
    protected MessageQueryService reads;

    @Autowired
    protected MembershipRepository memberships;

    @Autowired
    protected PresenceRepository presence;

    @Autowired
    protected VisibilityBackfillExecutor backfill;

    /** Аккаунт: идентификатор, логин и cookie сессии — всё, что нужно маршрутам участия. */
    protected final class Account {
        final UUID userId;
        final String login;
        final String token;

        private Account(UUID userId, String login, String token) {
            this.userId = userId;
            this.login = login;
            this.token = token;
        }
    }

    protected Account account(String prefix) throws Exception {
        return account(prefix, PASSWORD);
    }

    protected Account account(String prefix, String password) throws Exception {
        String login = unique(prefix);
        register(login, password, null);
        UUID userId = jdbc.queryForObject(
                "SELECT id FROM app_user WHERE login_normalized = ?",
                UUID.class,
                loginNormalized(login));
        return new Account(userId, login, loginAndGetToken(login, password));
    }

    /**
     * Игра мастера с двумя локациями и двумя играбельными персонажами, размещёнными
     * в первой локации. Готовность и старт выполняются вызывающим: `E13` различает
     * приглашение до старта и в середину `RUNNING` (`D-131`).
     */
    protected final class Fixture {
        final Account master;
        final UUID gameId;
        final UUID place;
        final UUID elsewhere;
        final UUID firstCharacter;
        final UUID secondCharacter;
        Account watcher;
        UUID third;

        private Fixture(
                Account master, UUID gameId, UUID place, UUID elsewhere,
                UUID firstCharacter, UUID secondCharacter) {
            this.master = master;
            this.gameId = gameId;
            this.place = place;
            this.elsewhere = elsewhere;
            this.firstCharacter = firstCharacter;
            this.secondCharacter = secondCharacter;
        }

        void makePublic() {
            mutations.changeVisibility(master.userId, gameId, Visibility.PUBLIC);
        }

        void ready() {
            mutations.applyTransition(master.userId, gameId, GameTransition.MARK_READY, null);
        }

        void start() {
            mutations.applyTransition(master.userId, gameId, GameTransition.START, null);
        }

        /** Занимаемость слота по базе, а не по ответу сервиса: правило живёт в колонке. */
        UUID occupantOf(UUID characterId) {
            return jdbc.queryForObject(
                    "SELECT occupant_membership_id FROM character WHERE id = ?",
                    UUID.class, characterId);
        }

        boolean isFree(UUID characterId) {
            return occupantOf(characterId) == null;
        }

        UUID membershipIdOf(UUID userId) {
            return jdbc.queryForObject(
                    "SELECT id FROM membership WHERE game_id = ? AND user_id = ?",
                    UUID.class, gameId, userId);
        }

        String statusOf(UUID userId) {
            return jdbc.queryForObject(
                    "SELECT status FROM membership WHERE game_id = ? AND user_id = ?",
                    String.class, gameId, userId);
        }

        UUID reservedOf(UUID userId) {
            return jdbc.queryForObject(
                    "SELECT reserved_character_id FROM membership WHERE game_id = ? AND user_id = ?",
                    UUID.class, gameId, userId);
        }

        long backfillPending(UUID userId) {
            return countRows(
                    "SELECT count(*) FROM membership WHERE game_id = ? AND user_id = ? "
                            + "AND backfill_pending = true",
                    gameId, userId);
        }
    }

    protected Fixture fixture() throws Exception {
        Account master = account("МастерУчастия");
        Game game = mutations.createGame(master.userId, "Игра участия " + unique(""), null, null);
        UUID place = mutations.createLocation(master.userId, game.id(), "Площадь", "Описание").id();
        UUID elsewhere = mutations.createLocation(master.userId, game.id(), "Причал", "Описание").id();
        Character first = mutations.createCharacter(
                master.userId, game.id(), "Первый-" + unique(""), null, null, null, null, true, false);
        Character second = mutations.createCharacter(
                master.userId, game.id(), "Второй-" + unique(""), null, null, null, null, true, false);
        mutations.placeCharacter(master.userId, game.id(), first.id(), place);
        mutations.placeCharacter(master.userId, game.id(), second.id(), place);
        return new Fixture(master, game.id(), place, elsewhere, first.id(), second.id());
    }

    /** Реплика от имени мастера: история локации, по которой наблюдатель досчитывается. */
    protected void masterSays(Fixture fixture, UUID chatId, String body) {
        writes.post(fixture.master.userId, fixture.gameId, chatId, null,
                MessageKind.MASTER, body, false);
    }

    protected long inboxRows(UUID membershipId, UUID chatId) {
        if (membershipId == null) {
            return 0;
        }
        return countRows(
                "SELECT count(*) FROM inbox_entry WHERE subject_kind = 'MEMBERSHIP' "
                        + "AND subject_id = ? AND chat_id = ?",
                membershipId, chatId);
    }
}
