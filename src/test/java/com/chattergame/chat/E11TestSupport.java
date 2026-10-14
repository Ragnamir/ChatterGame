package com.chattergame.chat;

import com.chattergame.character.Character;
import com.chattergame.game.Game;
import com.chattergame.game.GameMutationService;
import com.chattergame.game.GameTransition;
import com.chattergame.game.MembershipRole;
import com.chattergame.game.MembershipStatus;
import com.chattergame.message.MessageQueryService;
import com.chattergame.message.MessageService;
import com.chattergame.restriction.RestrictionKind;
import com.chattergame.user.E02TestSupport;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

/**
 * Общая обвязка тестов `E11`: живая игра с мастером, локациями и тремя игроками.
 *
 * <p>Три персонажа — потому что критерии приёмки этапа требуют сразу и пары «разные игроки»
 * (`D-097`), и отрицательных клеток «чужая локация» и «другая скрытность». Один персонаж
 * этого не показывает: условия шёпота проверяются на паре, а отказ обязан называть разную
 * причину в каждом случае.
 *
 * <p>Персонажи занимаются прямой записью `occupant_membership_id`: эндпоинт занятия — предмет
 * `E13` (`D-107`), а лимит и права проверяются для персонажа, которым кто-то играет; без
 * владельца проверять нечего. Так же поступает обвязка `E10`.
 */
abstract class E11TestSupport extends E02TestSupport {

    @Autowired
    protected GameMutationService mutations;

    @Autowired
    protected MessageService writes;

    @Autowired
    protected ChannelQueryService channels;

    @Autowired
    protected MessageQueryService reads;

    /** Тексты ленты чата, которые видит читающий: тела сообщений без системных проекций. */
    protected List<String> feedBodies(Fixture fixture, UUID chatId, Actor reader) {
        return reads.feed(reader.userId, fixture.gameId, chatId, reader.characterId, 0, 0).items().stream()
                .map(com.chattergame.message.FeedItem::body)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /** Игра `RUNNING` с двумя локациями и тремя персонажами, каждый за своим игроком. */
    protected final class Fixture {

        final UUID gameId;
        final UUID masterUserId;
        final UUID masterMembershipId;
        final UUID place;
        final UUID elsewhere;
        final Actor first;
        final Actor second;
        final Actor third;

        private Fixture(
                UUID gameId,
                UUID masterUserId,
                UUID masterMembershipId,
                UUID place,
                UUID elsewhere,
                Actor first,
                Actor second,
                Actor third) {
            this.gameId = gameId;
            this.masterUserId = masterUserId;
            this.masterMembershipId = masterMembershipId;
            this.place = place;
            this.elsewhere = elsewhere;
            this.first = first;
            this.second = second;
            this.third = third;
        }

        UUID location(String title) {
            return mutations.createLocation(masterUserId, gameId, title, null).id();
        }

        void move(UUID characterId, UUID chatId) {
            mutations.placeCharacter(masterUserId, gameId, characterId, chatId);
        }

        /** Скрытность ведёт мастер (`D-108`): игрок только описывает уход в тень. */
        void hide(UUID characterId, boolean hidden) {
            mutations.setCharacterStealth(masterUserId, gameId, characterId, hidden, "проверка E11");
        }

        /** Два персонажа разных игроков, оказавшиеся в одной локации и с одной скрытностью. */
        void together() {
            move(first.characterId, place);
            move(second.characterId, place);
            move(third.characterId, elsewhere);
        }

        String channelKindOf(UUID chatId) {
            return jdbc.queryForObject(
                    "SELECT kind FROM chat WHERE id = ?", String.class, chatId);
        }

        long membersOf(UUID chatId, String state) {
            return countRows(
                    "SELECT count(*) FROM chat_member WHERE chat_id = ? AND state = ?", chatId, state);
        }

        long messagesIn(UUID chatId) {
            return countRows("SELECT count(*) FROM message WHERE chat_id = ?", chatId);
        }
    }

    /** Игрок, его участие и его персонаж: одна связка, которую тест переносит целиком. */
    protected final class Actor {

        final UUID userId;
        final UUID membershipId;
        final UUID characterId;
        final String token;

        private Actor(UUID userId, UUID membershipId, UUID characterId, String token) {
            this.userId = userId;
            this.membershipId = membershipId;
            this.characterId = characterId;
            this.token = token;
        }
    }

    /** Игра `RUNNING` с мастером, двумя локациями и тремя игроками за своими персонажами. */
    protected Fixture runningGame() throws Exception {
        String masterLogin = unique("МастерЧата");
        registerAndGetToken(masterLogin, PASSWORD);
        UUID masterUserId = jdbc.queryForObject(
                "SELECT id FROM app_user WHERE login_normalized = ?",
                UUID.class,
                loginNormalized(masterLogin));

        Game game = mutations.createGame(masterUserId, "Чаты " + unique("E11"), null, null);
        UUID place = mutations.createLocation(masterUserId, game.id(), "Площадь", null).id();
        UUID elsewhere = mutations.createLocation(masterUserId, game.id(), "Причал", null).id();
        mutations.applyTransition(masterUserId, game.id(), GameTransition.MARK_READY, null);
        mutations.applyTransition(masterUserId, game.id(), GameTransition.START, null);

        UUID masterMembershipId = jdbc.queryForObject(
                "SELECT id FROM membership WHERE game_id = ? AND user_id = ?",
                UUID.class, game.id(), masterUserId);

        Actor first = actor(game.id(), masterUserId, "Первый");
        Actor second = actor(game.id(), masterUserId, "Второй");
        Actor third = actor(game.id(), masterUserId, "Третий");
        mutations.placeCharacter(masterUserId, game.id(), first.characterId, place);
        mutations.placeCharacter(masterUserId, game.id(), second.characterId, place);
        mutations.placeCharacter(masterUserId, game.id(), third.characterId, elsewhere);

        return new Fixture(
                game.id(), masterUserId, masterMembershipId, place, elsewhere, first, second, third);
    }

    private Actor actor(UUID gameId, UUID masterUserId, String prefix) throws Exception {
        String login = unique(prefix);
        register(login, OTHER_PASSWORD, null);
        UUID userId = jdbc.queryForObject(
                "SELECT id FROM app_user WHERE login_normalized = ?",
                UUID.class,
                loginNormalized(login));
        UUID membershipId = mutations.addMembership(
                masterUserId, gameId, userId, MembershipRole.PLAYER, MembershipStatus.JOINED).id();
        Character character = mutations.createCharacter(
                masterUserId, gameId, prefix + "-" + unique(""), null, null, null, null, true, false);
        jdbc.update("UPDATE character SET occupant_membership_id = ? WHERE id = ?",
                membershipId, character.id());
        return new Actor(userId, membershipId, character.id(), loginAndGetToken(login, OTHER_PASSWORD));
    }

    /** Наблюдатель: читает открытое, в личные каналы не допускается вовсе (`D-063`). */
    protected Actor observer(Fixture fixture) throws Exception {
        String login = unique("НаблюдательЧата");
        register(login, OTHER_PASSWORD, null);
        UUID userId = jdbc.queryForObject(
                "SELECT id FROM app_user WHERE login_normalized = ?",
                UUID.class,
                loginNormalized(login));
        UUID membershipId = mutations.addMembership(
                fixture.masterUserId, fixture.gameId, userId,
                MembershipRole.OBSERVER, MembershipStatus.JOINED).id();
        return new Actor(userId, membershipId, null, loginAndGetToken(login, OTHER_PASSWORD));
    }

    /** Запрет речи персонажу: им проверяется третье условие шёпота — разрешение говорить. */
    protected void mute(Fixture fixture, UUID characterId) {
        mutations.setRestriction(
                fixture.masterUserId,
                fixture.gameId,
                com.chattergame.message.SubjectKind.CHARACTER,
                characterId,
                RestrictionKind.NO_SPEECH,
                "немой",
                null);
    }

    protected long countEvents(UUID gameId, String type) {
        return countRows(
                "SELECT count(*) FROM game_event WHERE game_id = ? AND type = ?", gameId, type);
    }

    protected UUID masterDmOf(Fixture fixture, UUID membershipId) {
        return jdbc.queryForObject(
                "SELECT m.chat_id FROM chat_member m JOIN chat c ON c.id = m.chat_id "
                        + "WHERE c.game_id = ? AND c.kind = 'MASTER_DM' AND m.subject_id = ? "
                        + "AND m.subject_kind = 'MEMBERSHIP'",
                UUID.class,
                fixture.gameId,
                membershipId);
    }
}
