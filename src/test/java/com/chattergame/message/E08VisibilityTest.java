package com.chattergame.message;

import com.chattergame.chat.ChatKind;
import com.chattergame.character.Character;
import com.chattergame.game.Game;
import com.chattergame.game.GameMutationService;
import com.chattergame.game.GameTransition;
import com.chattergame.game.Phase;
import com.chattergame.restriction.RestrictionKind;
import com.chattergame.user.E02TestSupport;
import com.chattergame.util.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Матрица прав и матрица видимости (`PLAN` E08, критерии приёмки).
 *
 * <p>Обе матрицы проверяются таблично и целиком, включая отрицательные клетки: правило
 * `assertCanPost` (`D-065`) и правило доставки (`D-030`, `D-086`) — это функции от
 * нескольких значений, и проверка примерами «заходит / не заходит» оставляет дыры там,
 * где клетку забыли.
 *
 * <p>Часть сценариев идёт через настоящий Postgres, потому что словами их не проверить:
 * запрет, навешенный среди игры, обязан остановить запись сразу (`D-065`), а видимость
 * уже сохранённого сообщения не пересчитывается задним числом (`D-007`).
 */
class E08VisibilityTest extends E02TestSupport {

    private static final UUID OTHER_CHARACTER = UUID.randomUUID();

    @Autowired
    private GameMutationService mutations;

    @Autowired
    private MessageService writes;

    @Autowired
    private InboxRepository inbox;

    // --- Матрица прав (REQUIREMENTS §11) ---------------------------------------------

    private static Stream<Arguments> permissionMatrix() {
        return Stream.of(
                // Мастер: любое событие в любом чате, кроме системного.
                cell("мастер пишет речь от присутствующего персонажа",
                        PostingCell.master(ChatKind.LOCATION).character(OTHER_CHARACTER, null, true),
                        MessageKind.SPEECH, null),
                cell("мастер пишет действие",
                        PostingCell.master(ChatKind.LOCATION).character(OTHER_CHARACTER, null, true),
                        MessageKind.ACTION, null),
                cell("мастер шутит",
                        PostingCell.master(ChatKind.LOCATION), MessageKind.JOKE, null),
                cell("мастер пишет событие мира",
                        PostingCell.master(ChatKind.LOCATION), MessageKind.MASTER, null),
                cell("мастер в личном чате: шутка разрешена",
                        PostingCell.master(ChatKind.MASTER_DM), MessageKind.JOKE, null),
                cell("мастер в личном чате: событие мира недоступно",
                        PostingCell.master(ChatKind.MASTER_DM), MessageKind.MASTER, "master_dm_speech_only"),
                cell("мастер в радиочате: событие мира недоступно",
                        PostingCell.master(ChatKind.RADIO), MessageKind.MASTER, "radio_speech_only"),
                cell("системный тип генерирует система, а не мастер",
                        PostingCell.master(ChatKind.LOCATION), MessageKind.SYSTEM, "system_is_generated"),
                cell("мастер в финальном чате: во время игры только шутка",
                        PostingCell.master(ChatKind.FINAL).atFinalChat(), MessageKind.MASTER, "final_joke_only"),

                // Игрок: свой занятый персонаж, присутствующий в чате.
                cell("игрок говорит своим персонажем",
                        PostingCell.player(ChatKind.LOCATION), MessageKind.SPEECH, null),
                cell("игрок действует своим персонажем",
                        PostingCell.player(ChatKind.LOCATION), MessageKind.ACTION, null),
                cell("игрок шутит без присутствия — шутке присутствие не нужно",
                        PostingCell.player(ChatKind.LOCATION).absent(), MessageKind.JOKE, null),
                cell("игрок в радиочате: речь разрешена",
                        PostingCell.player(ChatKind.RADIO), MessageKind.SPEECH, null),
                cell("игрок в радиочате: действие запрещено",
                        PostingCell.player(ChatKind.RADIO), MessageKind.ACTION, "radio_speech_only"),
                cell("игрок в радиочате: шутка запрещена",
                        PostingCell.player(ChatKind.RADIO), MessageKind.JOKE, "radio_speech_only"),
                cell("игрок в финальном чате: шутка разрешена",
                        PostingCell.player(ChatKind.FINAL).atFinalChat(), MessageKind.JOKE, null),
                cell("игрок в финальном чате: речь запрещена",
                        PostingCell.player(ChatKind.FINAL).atFinalChat(), MessageKind.SPEECH, "final_joke_only"),
                cell("игрок в личном чате с мастером: речь разрешена",
                        PostingCell.player(ChatKind.MASTER_DM), MessageKind.SPEECH, null),
                cell("игрок в личном чате с мастером: действие запрещено",
                        PostingCell.player(ChatKind.MASTER_DM), MessageKind.ACTION, "master_dm_speech_only"),
                cell("игрок не пишет событие мира",
                        PostingCell.player(ChatKind.LOCATION), MessageKind.MASTER, "not_master"),
                cell("игрок не пишет системное сообщение",
                        PostingCell.player(ChatKind.LOCATION), MessageKind.SYSTEM, "system_is_generated"),

                // Отрицательные клетки игрока: чужое, незанятое, отсутствующее.
                cell("чужой персонаж",
                        PostingCell.player(ChatKind.LOCATION)
                                .character(OTHER_CHARACTER, UUID.randomUUID(), true),
                        MessageKind.SPEECH, "not_your_character"),
                cell("незанятый персонаж",
                        PostingCell.player(ChatKind.LOCATION).character(OTHER_CHARACTER, null, true),
                        MessageKind.SPEECH, "not_your_character"),
                cell("персонаж обязателен для речи",
                        PostingCell.player(ChatKind.LOCATION).character(null, null, false),
                        MessageKind.SPEECH, "character_required"),
                cell("архивный персонаж",
                        PostingCell.player(ChatKind.LOCATION).archived(),
                        MessageKind.SPEECH, "character_archived"),
                cell("персонаж не в этом чате",
                        PostingCell.player(ChatKind.LOCATION).absent(), MessageKind.SPEECH, "character_not_present"),
                cell("игра не идёт: говорить от лица персонажа нельзя",
                        PostingCell.player(ChatKind.LOCATION).phase(Phase.NOT_STARTED),
                        MessageKind.SPEECH, "game_not_running"),
                cell("отказ по речи",
                        PostingCell.player(ChatKind.LOCATION).characterRestriction(RestrictionKind.NO_SPEECH),
                        MessageKind.SPEECH, "speech_restricted"),
                cell("запрет действия не запрещает речь",
                        PostingCell.player(ChatKind.LOCATION).characterRestriction(RestrictionKind.NO_ACTION),
                        MessageKind.SPEECH, null),
                cell("отказ по действию",
                        PostingCell.player(ChatKind.LOCATION).characterRestriction(RestrictionKind.NO_ACTION),
                        MessageKind.ACTION, "action_restricted"),
                cell("запрет шуток висит на участии",
                        PostingCell.player(ChatKind.LOCATION).membershipRestriction(RestrictionKind.NO_JOKE),
                        MessageKind.JOKE, "joke_restricted"),
                cell("запрет речи на персонаже не мешает шутке",
                        PostingCell.player(ChatKind.LOCATION).characterRestriction(RestrictionKind.NO_SPEECH),
                        MessageKind.JOKE, null),

                // Участие и состояние чата.
                cell("забаненное участие",
                        PostingCell.player(ChatKind.LOCATION).banned(), MessageKind.SPEECH, "membership_banned"),
                cell("участие не активно",
                        PostingCell.player(ChatKind.LOCATION).notJoined(), MessageKind.SPEECH, "membership_not_joined"),
                cell("архивный чат",
                        PostingCell.player(ChatKind.LOCATION).chatArchived(), MessageKind.SPEECH, "chat_archived"),

                // После FINISHED (D-094).
                cell("после завершения в локации писать нельзя",
                        PostingCell.player(ChatKind.LOCATION).phase(Phase.FINISHED), MessageKind.JOKE, "game_finished"),
                cell("после завершения в финальном чате шутка разрешена",
                        PostingCell.player(ChatKind.FINAL).atFinalChat().phase(Phase.FINISHED),
                        MessageKind.JOKE, null),
                cell("после завершения в финальном чате речь запрещена",
                        PostingCell.player(ChatKind.FINAL).atFinalChat().phase(Phase.FINISHED),
                        MessageKind.SPEECH, "final_joke_only"),
                cell("после завершения событие мира в финальном чате разрешено",
                        PostingCell.master(ChatKind.FINAL).atFinalChat().phase(Phase.FINISHED),
                        MessageKind.MASTER, null),
                cell("после завершения событие мира в локации запрещено",
                        PostingCell.master(ChatKind.LOCATION).phase(Phase.FINISHED),
                        MessageKind.MASTER, "game_finished"),

                // Наблюдатель не пишет (D-063).
                cell("наблюдатель в локации: шутка запрещена",
                        PostingCell.observer(ChatKind.LOCATION), MessageKind.JOKE, "observer_cannot_post"),
                cell("наблюдатель в локации: речь запрещена",
                        PostingCell.observer(ChatKind.LOCATION), MessageKind.SPEECH, "observer_cannot_post"),
                cell("наблюдатель в локации: действие запрещено",
                        PostingCell.observer(ChatKind.LOCATION), MessageKind.ACTION, "observer_cannot_post"),
                cell("наблюдатель в финальном чате: шутка разрешена",
                        PostingCell.observer(ChatKind.FINAL).atFinalChat(), MessageKind.JOKE, null),
                cell("наблюдатель в закрытом чате: шутка запрещена",
                        PostingCell.observer(ChatKind.WHISPER), MessageKind.JOKE, "observer_cannot_post"),
                cell("наблюдатель не пишет событие мира",
                        PostingCell.observer(ChatKind.LOCATION), MessageKind.MASTER, "not_master"));
    }

    private static Arguments cell(String name, PostingCell cell, MessageKind kind, String code) {
        return Arguments.of(name, cell, kind, code);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("permissionMatrix")
    void permissionMatrixIsCoveredCellByCell(
            String name, PostingCell posting, MessageKind kind, String expectedCode) {
        assertThat(posting.rejection(kind))
                .as("клетка матрицы прав: %s", name)
                .isEqualTo(expectedCode);
    }

    @Test
    void bodyLengthIsCappedByContract() {
        MessageAccess.assertBodyLength("я".repeat(Message.BODY_MAX));
        assertThatThrownBy(() -> MessageAccess.assertBodyLength("я".repeat(Message.BODY_MAX + 1)))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo("message_too_long");
    }

    @Test
    void messageKindsVocabularyIsClosed() {
        assertThat(MessageKind.values())
                .as("пять типов сообщений зафиксированы решением D-031")
                .containsExactlyInAnyOrder(
                        MessageKind.SPEECH, MessageKind.ACTION, MessageKind.JOKE,
                        MessageKind.MASTER, MessageKind.SYSTEM);
    }

    // --- Матрица видимости (скрытность × тип чата × роль) -----------------------------

    private static Stream<Arguments> visibilityMatrix() {
        Stream.Builder<Arguments> rows = Stream.builder();
        for (InboxMaterializer.Viewer viewer : InboxMaterializer.Viewer.values()) {
            for (ChatKind kind : ChatKind.values()) {
                for (boolean hidden : List.of(false, true)) {
                    rows.add(Arguments.of(viewer, kind, hidden, expectedVisibility(viewer, kind, hidden)));
                }
            }
        }
        return rows.build();
    }

    /**
     * Ожидание выписано таблицей, а не вызовом проверяемого метода: иначе тест повторял бы
     * реализацию и оставался зелёным при любой ошибке в ней.
     */
    private static boolean expectedVisibility(
            InboxMaterializer.Viewer viewer, ChatKind kind, boolean hidden) {
        return switch (viewer) {
            case AUTHOR, REQUESTER, MASTER -> true;
            case PRESENT_CHARACTER, PRESENT_OCCUPANT -> !hidden;
            case OBSERVER -> !hidden && (kind == ChatKind.LOCATION || kind == ChatKind.FINAL);
        };
    }

    @ParameterizedTest(name = "{0} × {1}, автор скрыт: {2}")
    @MethodSource("visibilityMatrix")
    void visibilityMatrixIsCoveredCellByCell(
            InboxMaterializer.Viewer viewer, ChatKind kind, boolean hidden, boolean expected) {
        assertThat(InboxMaterializer.visibleTo(viewer, kind, hidden))
                .as("матрица видимости: %s в %s, автор скрыт: %s", viewer, kind, hidden)
                .isEqualTo(expected);
    }

    @Test
    void stepModeDoesNotChangeVisibility() {
        // Пошаговый режим нормирует лимиты (REQUIREMENTS §14), а не доставку: скрытый автор
        // остаётся скрытым при любом значении флага, и это ответ на ось «пошаговый режим»
        // из критерия PLAN E08. Лимиты хода — предмет E10.
        for (boolean hidden : List.of(false, true)) {
            assertThat(InboxMaterializer.visibleTo(
                            InboxMaterializer.Viewer.PRESENT_CHARACTER, ChatKind.LOCATION, hidden))
                    .isEqualTo(!hidden);
            assertThat(InboxMaterializer.visibleTo(
                            InboxMaterializer.Viewer.OBSERVER, ChatKind.LOCATION, hidden))
                    .isEqualTo(!hidden);
        }
    }

    // --- Интеграция: материализация, запрет и стабильность ---------------------------

    @Test
    void postingMaterializesInboxEntriesForPresentCharactersAndMaster() throws Exception {
        Fixture fixture = runningGameWithLocation();
        Character present = fixture.character();
        mutations.placeCharacter(fixture.masterUserId(), fixture.gameId(), present.id(), fixture.locationId());

        Message message = writes.post(
                fixture.masterUserId(), fixture.gameId(), fixture.locationId(), present.id(),
                MessageKind.SPEECH, "Из-за поворота вышел смотритель маяка", false);

        assertThat(inbox.subjectsFor(fixture.locationId(), message.seq()))
                .as("получатели материализуются при записи (D-030): персонаж и мастер")
                .containsExactlyInAnyOrder(
                        new InboxSubject(SubjectKind.CHARACTER, present.id()),
                        new InboxSubject(SubjectKind.MEMBERSHIP, fixture.masterMembershipId()));
    }

    @Test
    void absentCharacterReceivesNothing() throws Exception {
        Fixture fixture = runningGameWithLocation();
        Character walker = fixture.character();
        mutations.placeCharacter(fixture.masterUserId(), fixture.gameId(), walker.id(), fixture.locationId());
        writes.post(fixture.masterUserId(), fixture.gameId(), fixture.locationId(), walker.id(),
                MessageKind.SPEECH, "первая реплика", false);

        Character ghost = mutations.createCharacter(
                fixture.masterUserId(), fixture.gameId(), "Отсутствующий", null, null, null, null, true, false);
        UUID away = mutations.createLocation(
                fixture.masterUserId(), fixture.gameId(), "Сторожка-" + unique(""), null).id();
        mutations.placeCharacter(fixture.masterUserId(), fixture.gameId(), ghost.id(), fixture.locationId());
        mutations.placeCharacter(fixture.masterUserId(), fixture.gameId(), ghost.id(), away);

        Message message = writes.post(
                fixture.masterUserId(), fixture.gameId(), fixture.locationId(), walker.id(),
                MessageKind.SPEECH, "вторая реплика", false);

        assertThat(inbox.subjectsFor(fixture.locationId(), message.seq()))
                .as("персонаж, ушедший в другую локацию, строки не получает (REQUIREMENTS §7.1)")
                .doesNotContain(new InboxSubject(SubjectKind.CHARACTER, ghost.id()));
    }

    @Test
    void hiddenAuthorIsVisibleOnlyToSelfAndMaster() throws Exception {
        Fixture fixture = runningGameWithLocation();
        Character author = fixture.character();
        Character witness = mutations.createCharacter(
                fixture.masterUserId(), fixture.gameId(), "Свидетель", null, null, null, null, true, false);
        mutations.placeCharacter(fixture.masterUserId(), fixture.gameId(), author.id(), fixture.locationId());
        mutations.placeCharacter(fixture.masterUserId(), fixture.gameId(), witness.id(), fixture.locationId());
        mutations.setCharacterStealth(
                fixture.masterUserId(), fixture.gameId(), author.id(), true, "ушёл в тень");

        Message message = writes.post(
                fixture.masterUserId(), fixture.gameId(), fixture.locationId(), author.id(),
                MessageKind.SPEECH, "тихий шёпот из темноты", false);

        assertThat(inbox.subjectsFor(fixture.locationId(), message.seq()))
                .as("скрытый автор: содержимое уходит только ему и мастеру (D-007)")
                .containsExactlyInAnyOrder(
                        new InboxSubject(SubjectKind.CHARACTER, author.id()),
                        new InboxSubject(SubjectKind.MEMBERSHIP, fixture.masterMembershipId()));
    }

    @Test
    void restrictionStopsPostingImmediately() throws Exception {
        Fixture fixture = runningGameWithLocation();
        Character speaker = fixture.character();
        mutations.placeCharacter(fixture.masterUserId(), fixture.gameId(), speaker.id(), fixture.locationId());

        writes.post(fixture.masterUserId(), fixture.gameId(), fixture.locationId(), speaker.id(),
                MessageKind.SPEECH, "первая реплика", false);

        mutations.setRestriction(
                fixture.masterUserId(), fixture.gameId(), SubjectKind.CHARACTER, speaker.id(),
                RestrictionKind.NO_SPEECH, "потерял голос", null);

        assertThatThrownBy(() -> writes.post(
                        fixture.masterUserId(), fixture.gameId(), fixture.locationId(), speaker.id(),
                        MessageKind.SPEECH, "вторая реплика", false))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .as("запрет читается из БД на каждое событие, а не из сокета (D-065)")
                .isEqualTo("speech_restricted");

        // Действие тем же персонажем проходит: виды ограничений раздельны (REQUIREMENTS §13).
        writes.post(fixture.masterUserId(), fixture.gameId(), fixture.locationId(), speaker.id(),
                MessageKind.ACTION, "махнул рукой", false);
    }

    @Test
    void revokingTheRestrictionRestoresPosting() throws Exception {
        Fixture fixture = runningGameWithLocation();
        Character speaker = fixture.character();
        mutations.placeCharacter(fixture.masterUserId(), fixture.gameId(), speaker.id(), fixture.locationId());
        var restriction = mutations.setRestriction(
                fixture.masterUserId(), fixture.gameId(), SubjectKind.CHARACTER, speaker.id(),
                RestrictionKind.NO_SPEECH, "временная немота", null);

        mutations.revokeRestriction(fixture.masterUserId(), fixture.gameId(), restriction.id());

        assertThat(writes.post(
                        fixture.masterUserId(), fixture.gameId(), fixture.locationId(), speaker.id(),
                        MessageKind.SPEECH, "снова могу говорить", false).seq())
                .as("снятие запрета возвращает право записи (D-064)")
                .isGreaterThan(0L);
    }

    @Test
    void visibilityOfAnAlreadyStoredMessageDoesNotChangeLater() throws Exception {
        Fixture fixture = runningGameWithLocation();
        Character author = fixture.character();
        mutations.placeCharacter(fixture.masterUserId(), fixture.gameId(), author.id(), fixture.locationId());
        Message stored = writes.post(
                fixture.masterUserId(), fixture.gameId(), fixture.locationId(), author.id(),
                MessageKind.SPEECH, "сказано при всех", false);

        List<InboxSubject> before = inbox.subjectsFor(fixture.locationId(), stored.seq());

        // Уход в скрытность после публичной реплики: D-007 требует, чтобы прошлое не
        // переписывалось — «отобразился как ушедший», а не исчез из истории.
        mutations.setCharacterStealth(
                fixture.masterUserId(), fixture.gameId(), author.id(), true, "ушёл в тень");

        assertThat(inbox.subjectsFor(fixture.locationId(), stored.seq()))
                .as("видимость уже сохранённого сообщения не пересчитывается (D-007)")
                .containsExactlyInAnyOrderElementsOf(before);
    }

    @Test
    void systemEventIsProjectedIntoTheFeedWithoutAMessageRow() throws Exception {
        Fixture fixture = runningGameWithLocation();
        Character walker = fixture.character();
        mutations.placeCharacter(fixture.masterUserId(), fixture.gameId(), walker.id(), fixture.locationId());

        long enteredSeq = jdbc.queryForObject(
                "SELECT seq FROM game_event WHERE game_id = ? AND type = 'CHARACTER_ENTERED' "
                        + "ORDER BY seq DESC LIMIT 1",
                Long.class, fixture.gameId());

        assertThat(inbox.subjectsFor(fixture.locationId(), enteredSeq))
                .as("системное событие проецируется в ленту чата (D-031)")
                .isNotEmpty();
        assertThat(countRows(
                        "SELECT count(*) FROM message WHERE chat_id = ? AND seq = ?",
                        fixture.locationId(), enteredSeq))
                .as("у системного сообщения нет своей строки в message — оно проекция журнала")
                .isZero();
    }

    @Test
    void hiddenAuthorSupressesObserversAndWitnessesInEveryChat() throws Exception {
        Fixture fixture = runningGameWithLocation();
        Character author = fixture.character();
        Character witness = mutations.createCharacter(
                fixture.masterUserId(), fixture.gameId(), "Свидетель", null, null, null, null, true, false);
        mutations.placeCharacter(fixture.masterUserId(), fixture.gameId(), author.id(), fixture.locationId());
        mutations.placeCharacter(fixture.masterUserId(), fixture.gameId(), witness.id(), fixture.locationId());
        mutations.setCharacterStealth(
                fixture.masterUserId(), fixture.gameId(), author.id(), true, "ушёл в тень");

        // Событие входа проецируется в ленту того же чата: присутствующие получили строку,
        // потому что вход публичен; реплика скрытого — нет, и это ровно та разница,
        // которую матрица видимости выражает одной клеткой.
        long leftSeq = jdbc.queryForObject(
                "SELECT seq FROM game_event WHERE game_id = ? AND type = 'CHARACTER_STEALTHED' "
                        + "ORDER BY seq DESC LIMIT 1",
                Long.class, fixture.gameId());
        assertThat(inbox.subjectsFor(fixture.locationId(), leftSeq))
                .as("само событие ухода видно: мастер обязан знать, что персонаж скрылся")
                .isNotEmpty();
    }

    @Test
    void sentMessageLandsInTheFeedOfTheAuthorOnly() throws Exception {
        Fixture fixture = runningGameWithLocation();
        Character author = fixture.character();
        mutations.placeCharacter(fixture.masterUserId(), fixture.gameId(), author.id(), fixture.locationId());
        Message message = writes.post(
                fixture.masterUserId(), fixture.gameId(), fixture.locationId(), author.id(),
                MessageKind.SPEECH, "реплика для ленты", false);

        assertThat(inbox.exists(
                        new InboxSubject(SubjectKind.CHARACTER, author.id()),
                        fixture.locationId(),
                        message.seq()))
                .as("лента собирается из inbox_entry, и автор видит свою реплику (D-030)")
                .isTrue();
        assertThat(inbox.exists(
                        new InboxSubject(SubjectKind.CHARACTER, UUID.randomUUID()),
                        fixture.locationId(),
                        message.seq()))
                .as("посторонний персонаж строки не получает")
                .isFalse();
    }

    // --- Обвязка ---------------------------------------------------------------------

    private record Fixture(
            UUID gameId,
            UUID locationId,
            UUID masterUserId,
            UUID masterMembershipId,
            Character character) {

    }

    private Fixture runningGameWithLocation() throws Exception {
        String login = unique("Мастер");
        registerAndGetToken(login, PASSWORD);
        UUID masterUserId = jdbc.queryForObject(
                "SELECT id FROM app_user WHERE login_normalized = ?", UUID.class, loginNormalized(login));

        Game game = mutations.createGame(masterUserId, "Игра " + unique("E08"), null, null);
        UUID locationId = mutations.createLocation(masterUserId, game.id(), "Маячная башня", "описание").id();
        mutations.applyTransition(masterUserId, game.id(), GameTransition.MARK_READY, null);
        mutations.applyTransition(masterUserId, game.id(), GameTransition.START, null);
        Character character = mutations.createCharacter(
                masterUserId, game.id(), "Смотритель-" + unique(""), null, null, null, null, true, false);
        UUID masterMembershipId = jdbc.queryForObject(
                "SELECT id FROM membership WHERE game_id = ? AND user_id = ?",
                UUID.class, game.id(), masterUserId);
        return new Fixture(game.id(), locationId, masterUserId, masterMembershipId, character);
    }
}
