package com.chattergame.notification;

import com.chattergame.character.Character;
import com.chattergame.chat.Chat;
import com.chattergame.game.Game;
import com.chattergame.game.GameMutationService;
import com.chattergame.game.GameTransition;
import com.chattergame.game.MembershipRole;
import com.chattergame.game.MembershipStatus;
import com.chattergame.item.Item;
import com.chattergame.item.ItemSize;
import com.chattergame.item.ItemType;
import com.chattergame.item.Storage;
import com.chattergame.item.StorageKind;
import com.chattergame.message.MessageKind;
import com.chattergame.message.MessageService;
import com.chattergame.user.E02TestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * Заготовка уведомлений (`PLAN` O03, `D-070`, `D-148`).
 *
 * <p>Проверяется то, что нельзя увидеть по коду: адресат каждого события из списка,
 * отсутствие закрытого содержимого в уведомлении и то, что отправка не происходит.
 * Настоящего транспорта нет, поэтому «не происходит» проверяется отсутствием подменённого
 * получателя: {@link NotificationDryRun} только возвращает план.
 */
class O03NotificationTest extends E02TestSupport {

    @Autowired
    private GameMutationService mutations;

    @Autowired
    private MessageService messages;

    @Autowired
    private NotificationDryRun dryRun;

    @Autowired
    private NotificationPreferences preferences;

    @Autowired
    private com.chattergame.journal.GameEventRepository eventRepository;

    @Test
    void everyNotificationEventHasAChannelAndATextKey() {
        for (NotificationEvent event : NotificationEvent.values()) {
            assertThat(event.defaultChannel())
                    .as("у события %s должен быть канал по умолчанию", event)
                    .isNotNull();
            assertThat(event.textKey())
                    .as("текст уведомления берётся по ключу интернализации (D-081)")
                    .startsWith("notification.");
        }
        assertThat(NotificationEvent.values())
                .as("список событий совпадает с PLAN O03")
                .extracting(Enum::name)
                .containsExactlyInAnyOrder(
                        "WHISPER_INVITATION",
                        "MASTER_REPLY",
                        "TURN_OPENED",
                        "CHARACTER_REVEALED",
                        "ITEM_OFFERED");
    }

    @Test
    void whisperInvitationIsAddressedToTheInvitedPlayerOnly() throws Exception {
        Fixture fixture = game();
        Chat whisper = mutations.createWhisper(
                fixture.firstUserId, fixture.gameId,
                List.of(fixture.firstCharacter, fixture.secondCharacter));

        List<NotificationDryRun.Planned> planned = dryRun.dispatch(lastEvent(fixture.gameId, "CHAT_MEMBER_INVITED"));

        assertThat(planned)
                .as("приглашённый получает уведомление, инициатор — нет")
                .extracting(item -> item.candidate().userId())
                .containsExactly(fixture.secondUserId);
        assertThat(planned.get(0).channel()).isEqualTo(NotificationChannel.IN_APP);
        assertThat(planned.get(0).candidate().params())
                .as("в уведомлении только идентификаторы, а не текст шёпота (D-046)")
                .containsEntry("chatId", whisper.id().toString())
                .doesNotContainKeys("body", "text", "speakerName");
    }

    @Test
    void masterReplyNotifiesThePlayerButNotTheMaster() throws Exception {
        Fixture fixture = game();
        UUID dm = jdbc.queryForObject(
                "SELECT m.chat_id FROM chat_member m JOIN chat c ON c.id = m.chat_id "
                        + "WHERE c.game_id = ? AND c.kind = 'MASTER_DM' AND m.subject_id = ?",
                UUID.class,
                fixture.gameId,
                fixture.firstMembershipId);

        messages.post(fixture.masterUserId, fixture.gameId, dm, fixture.firstCharacter,
                MessageKind.SPEECH, "Ответ", false);

        List<NotificationDryRun.Planned> planned = dryRun.dispatch(lastEvent(fixture.gameId, "MESSAGE_POSTED"));

        assertThat(planned)
                .as("ответ мастера адресован игроку")
                .extracting(item -> item.candidate().userId())
                .containsExactly(fixture.firstUserId);
        assertThat(planned.get(0).candidate().params())
                .as("содержимое личного диалога в уведомление не попадает")
                .doesNotContainKeys("body", "text");
    }

    @Test
    void turnOpeningNotifiesPlayersAndSkipsTheObserver() throws Exception {
        Fixture fixture = game();
        mutations.setTurnBased(fixture.masterUserId, fixture.gameId, true);
        UUID observerUserId = observer(fixture);

        mutations.openTurn(fixture.masterUserId, fixture.gameId);

        List<NotificationDryRun.Planned> planned = dryRun.dispatch(lastEvent(fixture.gameId, "TURN_OPENED"));

        assertThat(planned)
                .as("ход открыт игрокам, а не наблюдателю и не мастеру")
                .extracting(item -> item.candidate().userId())
                .containsExactlyInAnyOrder(fixture.firstUserId, fixture.secondUserId)
                .doesNotContain(observerUserId, fixture.masterUserId);
    }

    @Test
    void revealingACharacterNotifiesThosePresentExceptItsOwner() throws Exception {
        Fixture fixture = game();
        mutations.placeCharacter(fixture.masterUserId, fixture.gameId, fixture.firstCharacter, fixture.location);
        mutations.placeCharacter(fixture.masterUserId, fixture.gameId, fixture.secondCharacter, fixture.location);
        mutations.setCharacterStealth(fixture.masterUserId, fixture.gameId, fixture.firstCharacter, true, "тень");
        mutations.setCharacterStealth(fixture.masterUserId, fixture.gameId, fixture.firstCharacter, false, "выход");

        assertThat(dryRun.dispatch(lastEvent(fixture.gameId, "CHARACTER_REVEALED")))
                .as("по умолчанию выход из скрытности молчит (D-148)")
                .isEmpty();

        preferences.update(
                fixture.secondUserId,
                Map.of(NotificationEvent.CHARACTER_REVEALED, NotificationChannel.IN_APP));

        assertThat(dryRun.dispatch(lastEvent(fixture.gameId, "CHARACTER_REVEALED")))
                .as("о выходе из тени узнаёт тот, кто стоит рядом")
                .extracting(item -> item.candidate().userId())
                .containsExactly(fixture.secondUserId);
    }

    @Test
    void movingAnItemIntoACharacterStorageNotifiesTheOwner() throws Exception {
        Fixture fixture = game();
        Storage from = mutations.createStorage(
                fixture.masterUserId, fixture.gameId, StorageKind.LOCATION,
                fixture.location, null, "Склад", null);
        Storage to = mutations.createStorage(
                fixture.masterUserId, fixture.gameId, StorageKind.CHARACTER_HIDDEN,
                null, fixture.secondCharacter, "Сумка", null);
        Item item = mutations.createItem(
                fixture.masterUserId, fixture.gameId, ItemType.MISC, "Письмо", null,
                null, ItemSize.SMALL, 1, null, from.id());

        mutations.moveItem(
                fixture.masterUserId, fixture.gameId, item.id(), from.id(), to.id(), null);

        List<NotificationDryRun.Planned> planned = dryRun.dispatch(lastEvent(fixture.gameId, "ITEM_MOVED"));

        assertThat(planned)
                .as("предмет в чужом хранилище — повод сказать владельцу")
                .extracting(item2 -> item2.candidate().userId())
                .containsExactly(fixture.secondUserId);
        assertThat(planned.get(0).candidate().params())
                .containsEntry("itemId", item.id().toString())
                .doesNotContainKeys("name", "description");
    }

    @Test
    void preferencesDefaultToTheCodeAndSurviveAPartialUpdate() throws Exception {
        String login = unique("Настройки");
        String token = registerAndGetToken(login, PASSWORD);
        UUID userId = jdbc.queryForObject(
                "SELECT id FROM app_user WHERE login_normalized = ?", UUID.class, loginNormalized(login));

        MvcResult initial = call(get("/me/notifications"), token, null);
        assertThat(initial.getResponse().getStatus()).isEqualTo(200);
        var initialBody = json.readTree(initial.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(initialBody.get("events")).hasSize(NotificationEvent.values().length);

        MvcResult updated = call(
                patch("/me/notifications"), token, Map.of("WHISPER_INVITATION", "EMAIL"));
        assertThat(updated.getResponse().getStatus()).isEqualTo(200);
        var updatedBody = json.readTree(updated.getResponse().getContentAsString(StandardCharsets.UTF_8));
        String whisperChannel = null;
        String turnChannel = null;
        for (var entry : updatedBody.get("events")) {
            if ("WHISPER_INVITATION".equals(entry.get("event").asText())) {
                whisperChannel = entry.get("channel").asText();
            }
            if ("TURN_OPENED".equals(entry.get("event").asText())) {
                turnChannel = entry.get("channel").asText();
            }
        }
        assertThat(whisperChannel).isEqualTo("EMAIL");
        assertThat(turnChannel)
                .as("частичная правка не сбрасывает остальные события")
                .isEqualTo(NotificationEvent.TURN_OPENED.defaultChannel().name());
        assertThat(preferences.channelFor(userId, NotificationEvent.WHISPER_INVITATION))
                .isEqualTo(NotificationChannel.EMAIL);
        assertThat(countRows(
                        "SELECT count(*) FROM notification_preference WHERE user_id = ?", userId))
                .as("одна строка на событие, а не история переключений")
                .isEqualTo(1);
    }

    @Test
    void unknownEventAndChannelAreRefusedInsteadOfSilentlyIgnored() throws Exception {
        String token = registerAndGetToken(unique("Опечатка"), PASSWORD);

        MvcResult badEvent = call(
                patch("/me/notifications"), token, Map.of("WHISPER_INVITATON", "EMAIL"));
        assertThat(badEvent.getResponse().getStatus())
                .as("опечатка в имени события не должна выглядеть сохранённой настройкой")
                .isEqualTo(400);

        MvcResult badChannel = call(
                patch("/me/notifications"), token, Map.of("WHISPER_INVITATION", "PUSH"));
        assertThat(badChannel.getResponse().getStatus())
                .as("push отложен решением D-070 и в словаре каналов его нет")
                .isEqualTo(400);
    }

    @Test
    void channelChosenByTheRecipientDecidesWhetherTheDryRunPlansAnything() throws Exception {
        Fixture fixture = game();
        preferences.update(fixture.secondUserId, Map.of(NotificationEvent.WHISPER_INVITATION, NotificationChannel.NONE));
        mutations.createWhisper(
                fixture.firstUserId, fixture.gameId,
                List.of(fixture.firstCharacter, fixture.secondCharacter));

        assertThat(dryRun.dispatch(lastEvent(fixture.gameId, "CHAT_MEMBER_INVITED")))
                .as("адресат выключил событие — отправлять нечего")
                .isEmpty();
    }

    private record Fixture(
            UUID gameId,
            UUID masterUserId,
            UUID location,
            UUID firstUserId,
            UUID firstMembershipId,
            UUID firstCharacter,
            UUID secondUserId,
            UUID secondCharacter) {
    }

    private Fixture game() throws Exception {
        String masterLogin = unique("МастерУведомлений");
        registerAndGetToken(masterLogin, PASSWORD);
        UUID masterUserId = jdbc.queryForObject(
                "SELECT id FROM app_user WHERE login_normalized = ?", UUID.class, loginNormalized(masterLogin));
        Game game = mutations.createGame(masterUserId, "Уведомления " + unique("O03"), null, null);
        UUID location = mutations.createLocation(masterUserId, game.id(), "Зал", null).id();
        mutations.applyTransition(masterUserId, game.id(), GameTransition.MARK_READY, null);
        mutations.applyTransition(masterUserId, game.id(), GameTransition.START, null);

        Actor first = actor(game.id(), masterUserId, "ПервыйУведомлённый");
        Actor second = actor(game.id(), masterUserId, "ВторойУведомлённый");
        mutations.placeCharacter(masterUserId, game.id(), first.characterId, location);
        mutations.placeCharacter(masterUserId, game.id(), second.characterId, location);

        return new Fixture(
                game.id(), masterUserId, location,
                first.userId, first.membershipId, first.characterId,
                second.userId, second.characterId);
    }

    private Actor actor(UUID gameId, UUID masterUserId, String prefix) throws Exception {
        String login = unique(prefix);
        registerAndGetToken(login, PASSWORD);
        UUID userId = jdbc.queryForObject(
                "SELECT id FROM app_user WHERE login_normalized = ?", UUID.class, loginNormalized(login));
        UUID membershipId = mutations.addMembership(
                masterUserId, gameId, userId, MembershipRole.PLAYER, MembershipStatus.JOINED).id();
        Character character = mutations.createCharacter(
                masterUserId, gameId, prefix + "-" + unique(""), null, null, null, null, true, false);
        jdbc.update("UPDATE character SET occupant_membership_id = ? WHERE id = ?", membershipId, character.id());
        return new Actor(userId, membershipId, character.id());
    }

    private UUID observer(Fixture fixture) throws Exception {
        String login = unique("НаблюдательУведомлений");
        registerAndGetToken(login, PASSWORD);
        UUID userId = jdbc.queryForObject(
                "SELECT id FROM app_user WHERE login_normalized = ?", UUID.class, loginNormalized(login));
        mutations.addMembership(
                fixture.masterUserId, fixture.gameId, userId,
                MembershipRole.OBSERVER, MembershipStatus.JOINED);
        return userId;
    }

    private com.chattergame.journal.GameEvent lastEvent(UUID gameId, String type) {
        Long seq = jdbc.queryForObject(
                "SELECT max(seq) FROM game_event WHERE game_id = ? AND type = ?",
                Long.class, gameId, type);
        return eventRepository.find(gameId, seq).orElseThrow();
    }

    private record Actor(UUID userId, UUID membershipId, UUID characterId) {
    }
}
