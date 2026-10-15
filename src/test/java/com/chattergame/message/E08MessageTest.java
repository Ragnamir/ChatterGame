package com.chattergame.message;

import com.chattergame.character.Character;
import com.chattergame.game.Game;
import com.chattergame.game.GameMutationService;
import com.chattergame.game.GameTransition;
import com.chattergame.user.E02TestSupport;
import com.chattergame.util.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Запись, вход в локацию, курсоры и объём материала (`PLAN` E08, задачи 5–9).
 *
 * <p>Матрицы прав и видимости покрыты в {@code E08VisibilityTest}; здесь проверяется то,
 * что вокруг них: событие расхода бюджета, флаг `on_behalf_of_master`, вход в локацию по
 * `D-032`, счётчики непрочитанного из `inbox_entry` (`D-036`) и объём строк при нескольких
 * персонажах и чатах — риск `PLAN R7`, который решено снимать здесь, а не в проде.
 */
class E08MessageTest extends E02TestSupport {

    @Autowired
    private GameMutationService mutations;

    @Autowired
    private MessageService writes;

    @Autowired
    private MessageQueryService reads;

    // --- Расход бюджета и от лица мастера ---------------------------------------------

    @Test
    void characterSpeechCarriesBudgetConsumedInTheEventPayload() throws Exception {
        Fixture fixture = runningGameWithLocation();
        Character speaker = fixture.character();
        mutations.placeCharacter(fixture.masterUserId(), fixture.gameId(), speaker.id(), fixture.locationId());

        Message speech = writes.post(
                fixture.masterUserId(), fixture.gameId(), fixture.locationId(), speaker.id(),
                MessageKind.SPEECH, "доброе утро", false);

        assertThat(payloadOf(speech.seq(), fixture.gameId()))
                .as("бюджет хода на E08 — только поле события; механика лимитов — E10 (PLAN E08 п. 7)")
                .containsEntry("budgetConsumed", true)
                .containsEntry("kind", "SPEECH");
    }

    @Test
    void jokeAndWorldEventDoNotCarryBudget() throws Exception {
        Fixture fixture = runningGameWithLocation();
        mutations.placeCharacter(
                fixture.masterUserId(), fixture.gameId(), fixture.character().id(), fixture.locationId());

        Message joke = writes.post(
                fixture.masterUserId(), fixture.gameId(), fixture.locationId(), null,
                MessageKind.JOKE, "а маяк-то не горит", false);
        Message world = writes.post(
                fixture.masterUserId(), fixture.gameId(), fixture.locationId(), null,
                MessageKind.MASTER, "свет погас", false);

        assertThat(payloadOf(joke.seq(), fixture.gameId()))
                .as("шутка лимит не расходует (REQUIREMENTS §14): расхода в событии нет вовсе")
                .doesNotContainKey("budgetConsumed");
        assertThat(payloadOf(world.seq(), fixture.gameId()))
                .as("событие мира лимит не расходует")
                .doesNotContainKey("budgetConsumed");
    }

    @Test
    void masterPostingOnBehalfOfACharacterIsMarked() throws Exception {
        Fixture fixture = runningGameWithLocation();
        Character puppet = fixture.character();
        mutations.placeCharacter(fixture.masterUserId(), fixture.gameId(), puppet.id(), fixture.locationId());

        Message message = writes.post(
                fixture.masterUserId(), fixture.gameId(), fixture.locationId(), puppet.id(),
                MessageKind.SPEECH, "голос смотрителя дрогнул", true);

        assertThat(countRows(
                        "SELECT count(*) FROM message WHERE id = ? AND on_behalf_of_master = true",
                        message.id()))
                .as("сообщение от лица персонажа мастером помечается (D-005)")
                .isEqualTo(1);
        assertThat(payloadOf(message.seq(), fixture.gameId()))
                .containsEntry("onBehalfOfMaster", true);
    }

    // --- Вход в локацию и курсоры -----------------------------------------------------

    @Test
    void enteringLocationShowsTheLastMessageAndMovesTheCursorPastHistory() throws Exception {
        Fixture fixture = runningGameWithLocation();
        Character speaker = fixture.character();
        mutations.placeCharacter(fixture.masterUserId(), fixture.gameId(), speaker.id(), fixture.locationId());

        writes.post(fixture.masterUserId(), fixture.gameId(), fixture.locationId(), speaker.id(),
                MessageKind.SPEECH, "первая", false);
        writes.post(fixture.masterUserId(), fixture.gameId(), fixture.locationId(), speaker.id(),
                MessageKind.SPEECH, "вторая", false);
        Message last = writes.post(
                fixture.masterUserId(), fixture.gameId(), fixture.locationId(), speaker.id(),
                MessageKind.SPEECH, "третья", false);

        Map<String, Object> entered =
                writes.enterChat(fixture.masterUserId(), fixture.gameId(), fixture.locationId(), speaker.id());

        assertThat(((Number) entered.get("cursor")).longValue())
                .as("курсор встаёт на текущий максимум: прошлое читается прочитанным (D-032)")
                .isEqualTo(last.seq());

        MessageQueryService.FeedPage afterEnter = reads.feed(
                fixture.masterUserId(), fixture.gameId(), fixture.locationId(), speaker.id(),
                last.seq(), 0);
        assertThat(afterEnter.items())
                .as("после входа ничего старого не догружается (D-032)")
                .isEmpty();
    }

    @Test
    void readCursorNeverMovesBackwards() throws Exception {
        Fixture fixture = runningGameWithLocation();
        Character speaker = fixture.character();
        mutations.placeCharacter(fixture.masterUserId(), fixture.gameId(), speaker.id(), fixture.locationId());
        Message first = writes.post(
                fixture.masterUserId(), fixture.gameId(), fixture.locationId(), speaker.id(),
                MessageKind.SPEECH, "раз", false);
        Message second = writes.post(
                fixture.masterUserId(), fixture.gameId(), fixture.locationId(), speaker.id(),
                MessageKind.SPEECH, "два", false);

        writes.markRead(fixture.masterUserId(), fixture.gameId(), fixture.locationId(), speaker.id(),
                second.seq());
        writes.markRead(fixture.masterUserId(), fixture.gameId(), fixture.locationId(), speaker.id(),
                first.seq());

        assertThat(reads.feed(
                        fixture.masterUserId(), fixture.gameId(), fixture.locationId(), speaker.id(),
                        0, 0)
                .readSeq())
                .as("отметка назад не откатывает курсор — иначе строки вернулись бы как непрочитанные")
                .isEqualTo(second.seq());
    }

    @Test
    void markReadCannotRunAheadOfTheFeed() throws Exception {
        Fixture fixture = runningGameWithLocation();
        Character speaker = fixture.character();
        mutations.placeCharacter(fixture.masterUserId(), fixture.gameId(), speaker.id(), fixture.locationId());
        Message last = writes.post(
                fixture.masterUserId(), fixture.gameId(), fixture.locationId(), speaker.id(),
                MessageKind.SPEECH, "последняя", false);

        writes.markRead(fixture.masterUserId(), fixture.gameId(), fixture.locationId(), speaker.id(),
                last.seq() + 1000);

        assertThat(reads.feed(
                        fixture.masterUserId(), fixture.gameId(), fixture.locationId(), speaker.id(),
                        0, 0)
                .readSeq())
                .as("курсор за пределами ленты дал бы «прочитано больше, чем выдано»")
                .isEqualTo(last.seq());
    }

    @Test
    void unreadCountersAreDerivedFromInboxEntries() throws Exception {
        Fixture fixture = runningGameWithLocation();
        Character speaker = fixture.character();
        mutations.placeCharacter(fixture.masterUserId(), fixture.gameId(), speaker.id(), fixture.locationId());

        Message first = writes.post(
                fixture.masterUserId(), fixture.gameId(), fixture.locationId(), speaker.id(),
                MessageKind.SPEECH, "раз", false);
        Message second = writes.post(
                fixture.masterUserId(), fixture.gameId(), fixture.locationId(), speaker.id(),
                MessageKind.ACTION, "два", false);

        long rows = countRows(
                "SELECT count(*) FROM inbox_entry WHERE subject_kind = 'CHARACTER' AND subject_id = ? "
                        + "AND chat_id = ?",
                speaker.id(), fixture.locationId());
        assertThat(unreadCount(fixture, speaker))
                .as("непрочитанное — это ровно строки inbox_entry субъекта (D-036)")
                .isEqualTo(rows);

        writes.markRead(
                fixture.masterUserId(), fixture.gameId(), fixture.locationId(), speaker.id(), second.seq());

        assertThat(unreadCount(fixture, speaker))
                .as("курсор закрывает прочитанное; счётчик берётся из inbox_entry, а не из сокета")
                .isZero();
        assertThat(second.seq()).isGreaterThan(first.seq());
    }

    // --- Отзыв сообщений и объём материала -------------------------------------------

    @Test
    void thereIsNoRevokeApiOnMessages() throws IOException {
        try (var stream = Files.walk(Paths.get("src/main/java/com/chattergame/message"))) {
            List<String> offenders = stream
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> {
                        try {
                            String code = Files.readString(path, java.nio.charset.StandardCharsets.UTF_8);
                            // `matched_note_ids` — досчёт подсветки заметок (`D-087`), а не
                            // правка содержимого: сообщение остаётся неизменным (`D-008`).
                            return code.contains("@DeleteMapping")
                                    || code.contains("@PatchMapping")
                                    || code.contains("UPDATE message SET body")
                                    || code.contains("DELETE FROM message");
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .map(Path::toString)
                    .toList();
            assertThat(offenders)
                    .as("отзыв и правка содержимого сообщений отсутствуют как API (D-008)")
                    .isEmpty();
        }
    }

    /**
     * Риск `PLAN R7`: объём `inbox_entry` при нескольких персонажах и чатах. Проверка здесь,
     * а не в проде: 10 персонажей × 50 чатов — это 500 присутствий и 550 строк на одну
     * реплику в каждом чате. Числа проверяются точно, иначе тест «не падает» и ничего не
     * гарантирует.
     */
    @Test
    void inboxVolumeStaysLinearWithCharactersAndChats() throws Exception {
        Fixture fixture = runningGameWithLocation();
        int characters = 10;
        int chats = 50;

        List<Character> cast = new ArrayList<>();
        for (int i = 0; i < characters; i++) {
            cast.add(mutations.createCharacter(
                    fixture.masterUserId(), fixture.gameId(), "Персонаж-" + i,
                    null, null, null, null, true, false));
        }
        // Присутствие у персонажа одно (`REQUIREMENTS §7.1`): он стоит в одном чате. Поэтому
        // «10 персонажей в чате» и «50 чатов» проверяются двумя разными раскладками, а не
        // одной невозможной.
        for (Character member : cast) {
            mutations.placeCharacter(
                    fixture.masterUserId(), fixture.gameId(), member.id(), fixture.locationId());
        }
        writes.post(fixture.masterUserId(), fixture.gameId(), fixture.locationId(), cast.getFirst().id(),
                MessageKind.SPEECH, "реплика при десяти свидетелях", false);

        assertThat(countRows(
                        "SELECT count(*) FROM inbox_entry "
                                + "WHERE chat_id = ? AND message_id IS NOT NULL",
                        fixture.locationId()))
                .as("реплика в чате с 10 персонажами = 10 строк персонажей + 1 строка мастера")
                .isEqualTo(characters + 1L);
        assertThat(countRows(
                        "SELECT count(*) FROM message WHERE chat_id = ?",
                        fixture.locationId()))
                .as("материал не размножается по получателям: одна реплика — одна строка (D-030)")
                .isEqualTo(1L);

        List<UUID> chatIds = new ArrayList<>();
        for (int i = 1; i < chats; i++) {
            UUID chatId = mutations.createLocation(
                    fixture.masterUserId(), fixture.gameId(), "Локация-" + i, null).id();
            chatIds.add(chatId);
            Character walker = cast.get(i % characters);
            mutations.placeCharacter(fixture.masterUserId(), fixture.gameId(), walker.id(), chatId);
            writes.post(fixture.masterUserId(), fixture.gameId(), chatId, walker.id(),
                    MessageKind.SPEECH, "реплика в чате " + i, false);
        }

        String placeholders = chatIds.stream().map(id -> "?").collect(Collectors.joining(", "));
        assertThat(countRows(
                        "SELECT count(*) FROM message WHERE chat_id IN (" + placeholders + ")",
                        chatIds.toArray()))
                .as("на 49 чатов — 49 строк сообщений и 49 × 2 строк inbox_entry")
                .isEqualTo(chatIds.size());
        assertThat(countRows(
                        "SELECT count(*) FROM inbox_entry "
                                + "WHERE chat_id IN (" + placeholders + ") AND message_id IS NOT NULL",
                        chatIds.toArray()))
                .as("объём растёт линейно по (персонажи × чаты), а не квадратично (PLAN риск R7)")
                .isEqualTo(chatIds.size() * 2L);
    }

    @Test
    void postingToAChatOfAnotherGameIsNotFound() throws Exception {
        Fixture first = runningGameWithLocation();
        Fixture second = runningGameWithLocation();
        mutations.placeCharacter(
                first.masterUserId(), first.gameId(), first.character().id(), first.locationId());

        assertThatThrownBy(() -> writes.post(
                        first.masterUserId(), first.gameId(), second.locationId(), null,
                        MessageKind.MASTER, "чужой чат", false))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo("chat_not_found");
    }

    // --- Обвязка ---------------------------------------------------------------------

    private long unreadCount(Fixture fixture, Character subject) {
        return reads.unread(fixture.masterUserId(), fixture.gameId(), subject.id()).stream()
                .filter(row -> row.chatId().equals(fixture.locationId()))
                .mapToLong(InboxRepository.Unread::count)
                .sum();
    }

    private Map<String, Object> payloadOf(long seq, UUID gameId) {
        String text = jdbc.queryForObject(
                "SELECT payload::text FROM game_event WHERE game_id = ? AND seq = ?",
                String.class, gameId, seq);
        try {
            return json.readValue(text, new com.fasterxml.jackson.core.type.TypeReference<>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("payload события не разобран: " + text, e);
        }
    }

    private record Fixture(UUID gameId, UUID locationId, UUID masterUserId, Character character) {
    }

    private Fixture runningGameWithLocation() throws Exception {
        String login = unique("Мастер");
        registerAndGetToken(login, PASSWORD);
        UUID masterUserId = jdbc.queryForObject(
                "SELECT id FROM app_user WHERE login_normalized = ?", UUID.class, loginNormalized(login));

        Game game = mutations.createGame(masterUserId, "Игра " + unique("E08"), null, null);
        UUID locationId = mutations.createLocation(masterUserId, game.id(), "Маячная башня", null).id();
        mutations.applyTransition(masterUserId, game.id(), GameTransition.MARK_READY, null);
        mutations.applyTransition(masterUserId, game.id(), GameTransition.START, null);
        Character character = mutations.createCharacter(
                masterUserId, game.id(), "Смотритель-" + unique(""), null, null, null, null, true, false);
        return new Fixture(game.id(), locationId, masterUserId, character);
    }
}
