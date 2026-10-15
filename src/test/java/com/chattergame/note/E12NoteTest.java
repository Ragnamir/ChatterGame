package com.chattergame.note;

import com.chattergame.character.Character;
import com.chattergame.game.Game;
import com.chattergame.game.GameMutationService;
import com.chattergame.game.GameTransition;
import com.chattergame.game.Membership;
import com.chattergame.game.MembershipRole;
import com.chattergame.game.MembershipStatus;
import com.chattergame.message.Message;
import com.chattergame.message.MessageKind;
import com.chattergame.message.MessageQueryService;
import com.chattergame.message.MessageService;
import com.chattergame.message.SubjectKind;
import com.chattergame.user.E02TestSupport;
import com.chattergame.util.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Тетрадь: заметки, подсветка и сохранённые сообщения (`PLAN` E12, задачи 2–8).
 *
 * <p>Проверяются критерии приёмки этапа: подсветка старых сообщений досчётом и её область,
 * ограниченная `inbox_entry` целевого субъекта; признак `matching_pending` до завершения
 * досчёта; снимок сохранённого сообщения после правки карточки мастером; видимость заметок
 * мастером и их отсутствие у наблюдателя.
 *
 * <p>Досчёт вызывается синхронно ({@link NoteBackfillExecutor#runNow()}), а не ожиданием
 * фоновой задачи: тест, спящий в ожидании потока, на медленной машине падает, а на быстрой
 * проходит мимо цели. Асинхронность же проверяется по признаку `matchingPending` — она
 * часть контракта, а не способ исполнения.
 */
class E12NoteTest extends E02TestSupport {

    @Autowired
    private GameMutationService mutations;

    @Autowired
    private MessageService writes;

    @Autowired
    private MessageQueryService reads;

    @Autowired
    private NoteService notes;

    @Autowired
    private SavedMessageService saved;

    @Autowired
    private NoteBackfillExecutor backfill;

    // --- Подсветка при записи и досчёт -------------------------------------------------

    @Test
    void keywordHighlightAppearsOnNewMessagesAtWriteTime() throws Exception {
        Fixture fixture = runningGame();
        notes.create(fixture.masterUserId, fixture.gameId, new NoteService.Draft(
                SubjectKind.CHARACTER, fixture.first.characterId, "запомнить",
                List.of(new NoteService.Draft.Keyword("козлоногий", false))));

        Message message = writes.post(
                fixture.masterUserId, fixture.gameId, fixture.place, fixture.first.characterId,
                MessageKind.SPEECH, "он был с козлоногим уродом", false);

        assertThat(feedOf(fixture, fixture.first).getFirst().matchedNoteIds())
                .as("ключ подсвечен в момент записи, а не на чтении (D-050)")
                .hasSize(1);
        assertThat(message.matchedNoteIds())
                .as("подсветка лежит в самой строке сообщения")
                .hasSize(1);
    }

    @Test
    void backfillHighlightsOlderMessagesAndClearsThePendingFlag() throws Exception {
        Fixture fixture = runningGame();
        writes.post(fixture.masterUserId, fixture.gameId, fixture.place, fixture.first.characterId,
                MessageKind.SPEECH, "с козлоногим уродом на плечах", false);

        Note.WithKeywords created = notes.create(fixture.masterUserId, fixture.gameId, new NoteService.Draft(
                SubjectKind.CHARACTER, fixture.first.characterId, "запомнить",
                List.of(new NoteService.Draft.Keyword("козлоногий", false))));

        assertThat(created.note().matchingPending())
                .as("до досчёта заметка помечена: клиент покажет «подсветка готовится» (D-087)")
                .isTrue();
        assertThat(feedOf(fixture, fixture.first).getFirst().matchedNoteIds())
                .as("до досчёта подсветки нет — и это не молчаливый пустой список")
                .isEmpty();

        NoteBackfillService.Report report = backfill.runNow(noteById(fixture, created.note().id()));

        assertThat(report.rows())
                .as("область досчёта — строки материала одного субъекта, а не всей игры")
                .isEqualTo(1);
        assertThat(feedOf(fixture, fixture.first).getFirst().matchedNoteIds())
                .as("старое сообщение подсвечено после досчёта (D-087)")
                .containsExactly(created.note().id());
        assertThat(noteById(fixture, created.note().id()).matchingPending())
                .as("признак снят по завершении досчёта")
                .isFalse();
    }

    @Test
    void backfillScopeIsLimitedToTheTargetSubjectsInbox() throws Exception {
        Fixture fixture = runningGame();
        // Три реплики: две адресованы первому персонажу, третья — третьему. Область досчёта
        // обязана совпасть с двумя, а не с тремя: иначе пересчёт идёт по всей игре.
        writes.post(fixture.masterUserId, fixture.gameId, fixture.place, fixture.first.characterId,
                MessageKind.SPEECH, "козлоногий раз", false);
        writes.post(fixture.masterUserId, fixture.gameId, fixture.place, fixture.first.characterId,
                MessageKind.SPEECH, "козлоногий два", false);
        writes.post(fixture.masterUserId, fixture.gameId, fixture.elsewhere, fixture.third.characterId,
                MessageKind.SPEECH, "козлоногий три", false);

        Note.WithKeywords created = notes.create(fixture.masterUserId, fixture.gameId, new NoteService.Draft(
                SubjectKind.CHARACTER, fixture.first.characterId, "запомнить",
                List.of(new NoteService.Draft.Keyword("козлоногий", false))));

        NoteBackfillService.Report report = backfill.runNow(noteById(fixture, created.note().id()));

        assertThat(report.rows())
                .as("досчёт ограничен inbox_entry целевого субъекта — две строки, а не три (D-087)")
                .isEqualTo(2);
    }

    @Test
    void editingAKeywordMovesTheHighlightToTheNewAsItsOldIsCleared() throws Exception {
        Fixture fixture = runningGame();
        Note.WithKeywords created = notes.create(fixture.masterUserId, fixture.gameId, new NoteService.Draft(
                SubjectKind.CHARACTER, fixture.first.characterId, "запомнить",
                List.of(new NoteService.Draft.Keyword("козлоногий", false))));
        writes.post(fixture.masterUserId, fixture.gameId, fixture.place, fixture.first.characterId,
                MessageKind.SPEECH, "с козлоногим уродом", false);
        backfill.runNow(noteById(fixture, created.note().id()));
        writes.post(fixture.masterUserId, fixture.gameId, fixture.place, fixture.first.characterId,
                MessageKind.SPEECH, "маяк погас", false);

        notes.update(fixture.masterUserId, fixture.gameId, created.note().id(), new NoteService.Draft(
                SubjectKind.CHARACTER, fixture.first.characterId, "запомнить",
                List.of(new NoteService.Draft.Keyword("маяк", false))));
        backfill.runNow(noteById(fixture, created.note().id()));

        List<com.chattergame.message.FeedItem> feed = feedOf(fixture, fixture.first);
        assertThat(feed.get(0).matchedNoteIds())
                .as("снятый ключ перестаёт подсвечивать при досчёте (D-087)")
                .isEmpty();
        assertThat(feed.get(1).matchedNoteIds())
                .as("новый ключ подсвечивает то, что назвал")
                .containsExactly(created.note().id());
    }

    // --- Конфликт ключей и слияние ---------------------------------------------------

    @Test
    void twoNotesOnTheSameKeywordAreBothReturned() throws Exception {
        Fixture fixture = runningGame();
        Note.WithKeywords first = notes.create(fixture.masterUserId, fixture.gameId, new NoteService.Draft(
                SubjectKind.CHARACTER, fixture.first.characterId, "первая",
                List.of(new NoteService.Draft.Keyword("маяк", false))));
        Note.WithKeywords second = notes.create(fixture.masterUserId, fixture.gameId, new NoteService.Draft(
                SubjectKind.CHARACTER, fixture.first.characterId, "вторая",
                List.of(new NoteService.Draft.Keyword("маяк", false))));
        writes.post(fixture.masterUserId, fixture.gameId, fixture.place, fixture.first.characterId,
                MessageKind.SPEECH, "маяк погас", false);
        backfill.runNow();

        assertThat(feedOf(fixture, fixture.first).getFirst().matchedNoteIds())
                .as("конфликт ключей показывает все заметки списком, а не одну (D-052)")
                .containsExactlyInAnyOrder(first.note().id(), second.note().id());
    }

    @Test
    void playerWithTwoCharactersSeesBothNotesMarkedByTarget() throws Exception {
        Fixture fixture = runningGame();
        Character second = mutations.createCharacter(
                fixture.masterUserId, fixture.gameId, "ВторойПерсонаж-" + unique(""),
                null, null, null, null, true, false);
        jdbc.update("UPDATE character SET occupant_membership_id = ? WHERE id = ?",
                fixture.first.membershipId, second.id());

        notes.create(fixture.first.userId, fixture.gameId, new NoteService.Draft(
                SubjectKind.CHARACTER, fixture.first.characterId, "за первого",
                List.of(new NoteService.Draft.Keyword("маяк", false))));
        notes.create(fixture.first.userId, fixture.gameId, new NoteService.Draft(
                SubjectKind.CHARACTER, second.id(), "за второго",
                List.of(new NoteService.Draft.Keyword("причал", false))));

        List<Note.WithKeywords> visible = notes.visible(fixture.first.userId, fixture.gameId);

        assertThat(visible)
                .as("объединение заметок обоих персонажей одного игрока (D-053)")
                .hasSize(2);
        assertThat(visible.stream().map(merged -> merged.note().target().id()).toList())
                .as("маркер владельца — цель заметки: клиент отличает, за кого какая запись (D-053)")
                .containsExactlyInAnyOrder(fixture.first.characterId, second.id());
    }

    // --- Видимость ---------------------------------------------------------------------

    @Test
    void masterSeesPlayerNotesAndObserverSeesNone() throws Exception {
        Fixture fixture = runningGame();
        Note.WithKeywords mine = notes.create(fixture.first.userId, fixture.gameId, new NoteService.Draft(
                SubjectKind.CHARACTER, fixture.first.characterId, "заметка игрока",
                List.of(new NoteService.Draft.Keyword("маяк", false))));
        Actor observer = observer(fixture);

        assertThat(notes.visible(fixture.masterUserId, fixture.gameId))
                .as("мастер видит заметки игроков (D-054)")
                .extracting(merged -> merged.note().id())
                .contains(mine.note().id());
        assertThat(notes.visible(observer.userId, fixture.gameId))
                .as("наблюдателю заметок нет вовсе (D-063)")
                .isEmpty();
        assertThatThrownBy(() -> notes.create(observer.userId, fixture.gameId, new NoteService.Draft(
                        SubjectKind.CHARACTER, fixture.first.characterId, "нельзя",
                        List.of(new NoteService.Draft.Keyword("маяк", false)))))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo("notes_not_for_observer");
    }

    @Test
    void playerCannotTargetSomeoneElsesCharacter() throws Exception {
        Fixture fixture = runningGame();

        assertThatThrownBy(() -> notes.create(fixture.first.userId, fixture.gameId, new NoteService.Draft(
                        SubjectKind.CHARACTER, fixture.second.characterId, "чужое",
                        List.of(new NoteService.Draft.Keyword("маяк", false)))))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo("note_target_not_allowed");
    }

    @Test
    void onlyTheAuthorCanEditANoteButTheMasterStillSeesIt() throws Exception {
        Fixture fixture = runningGame();
        Note.WithKeywords mine = notes.create(fixture.first.userId, fixture.gameId, new NoteService.Draft(
                SubjectKind.CHARACTER, fixture.first.characterId, "заметка игрока",
                List.of(new NoteService.Draft.Keyword("маяк", false))));

        assertThatThrownBy(() -> notes.update(fixture.masterUserId, fixture.gameId, mine.note().id(),
                        new NoteService.Draft(SubjectKind.CHARACTER, fixture.first.characterId, "правка",
                                List.of())))
                .as("мастер читает чужие заметки, но не переписывает их (D-054)")
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo("not_note_author");
    }

    @Test
    void limitsOnBodyAndKeywordsAreEnforced() throws Exception {
        Fixture fixture = runningGame();

        assertThatThrownBy(() -> notes.create(fixture.masterUserId, fixture.gameId, new NoteService.Draft(
                        SubjectKind.CHARACTER, fixture.first.characterId, "x".repeat(Note.BODY_MAX + 1),
                        List.of())))
                .as("текст заметки ограничен 2000 знаками (D-098)")
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo("note_body_too_long");

        List<NoteService.Draft.Keyword> tooMany = new java.util.ArrayList<>();
        for (int i = 0; i <= Note.KEYWORDS_MAX; i++) {
            tooMany.add(new NoteService.Draft.Keyword("слово" + i, false));
        }
        assertThatThrownBy(() -> notes.create(fixture.masterUserId, fixture.gameId, new NoteService.Draft(
                        SubjectKind.CHARACTER, fixture.first.characterId, "текст", tooMany)))
                .as("ключевых слов не больше десяти (D-098)")
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo("note_keywords_too_many");
    }

    // --- Сохранённые сообщения ---------------------------------------------------------

    @Test
    void savedMessageKeepsItsTextAfterTheMasterRenamesTheCharacter() throws Exception {
        Fixture fixture = runningGame();
        String originalName = jdbc.queryForObject(
                "SELECT name FROM character WHERE id = ?", String.class, fixture.first.characterId);
        writes.post(fixture.masterUserId, fixture.gameId, fixture.place, fixture.first.characterId,
                MessageKind.SPEECH, "я был на маяке", false);
        UUID messageId = messageIdOf(fixture, "я был на маяке");

        SavedMessage record = saved.save(fixture.first.userId, fixture.gameId,
                new SavedMessageService.SaveRequest(messageId, "важно", null));
        assertThat(record.speakerSnapshot())
                .as("имя говорящего снято в момент сохранения")
                .isEqualTo(originalName);

        mutations.updateCharacterCard(
                fixture.masterUserId, fixture.gameId, fixture.first.characterId,
                "НовоеИмя-" + unique(""), null, null, null, null, true, false);

        SavedMessage stored = saved.mine(fixture.first.userId, fixture.gameId).getFirst();
        assertThat(stored.textSnapshot())
                .as("снимок текста защищает запись от молчаливой правки (D-055)")
                .isEqualTo("я был на маяке");
        assertThat(stored.speakerSnapshot())
                .as("переименование персонажа не меняет уже сделанную запись (D-055)")
                .isEqualTo(originalName);
    }

    @Test
    void observerCanKeepSavedMessagesButNotNotes() throws Exception {
        Fixture fixture = runningGame();
        Actor observer = observer(fixture);
        writes.post(fixture.masterUserId, fixture.gameId, fixture.place, fixture.first.characterId,
                MessageKind.SPEECH, "открытая реплика", false);
        UUID messageId = messageIdOf(fixture, "открытая реплика");
        // Наблюдатель читает открытую локацию участием, и строка ему действительно выдана.
        assertThat(reads.feed(observer.userId, fixture.gameId, fixture.place, null, 0, 0).items())
                .as("открытая локация видна наблюдателю участием (D-063)")
                .isNotEmpty();

        SavedMessage record = saved.save(observer.userId, fixture.gameId,
                new SavedMessageService.SaveRequest(messageId, null, null));

        assertThat(record.textSnapshot())
                .as("сохранение сообщений наблюдателю доступно — это свойство игрока (D-063)")
                .isEqualTo("открытая реплика");
    }

    @Test
    void savingIsIdempotentPerOwnerAndMessage() throws Exception {
        Fixture fixture = runningGame();
        writes.post(fixture.masterUserId, fixture.gameId, fixture.place, fixture.first.characterId,
                MessageKind.SPEECH, "одна реплика", false);
        UUID messageId = messageIdOf(fixture, "одна реплика");

        saved.save(fixture.first.userId, fixture.gameId,
                new SavedMessageService.SaveRequest(messageId, "первый", null));
        saved.save(fixture.first.userId, fixture.gameId,
                new SavedMessageService.SaveRequest(messageId, "уточнение", null));

        assertThat(saved.mine(fixture.first.userId, fixture.gameId))
                .as("повтор — уточнение комментария, а не вторая запись (D-055)")
                .hasSize(1)
                .extracting(SavedMessage::comment)
                .containsExactly("уточнение");
    }

    @Test
    void savingAMessageOutOfOnesFeedIsNotFound() throws Exception {
        Fixture fixture = runningGame();
        writes.post(fixture.masterUserId, fixture.gameId, fixture.elsewhere, fixture.third.characterId,
                MessageKind.SPEECH, "реплика в другой локации", false);
        UUID messageId = messageIdOf(fixture, "реплика в другой локации");

        assertThatThrownBy(() -> saved.save(fixture.first.userId, fixture.gameId,
                        new SavedMessageService.SaveRequest(messageId, null, null)))
                .as("сохранить можно только выданное субъекту: тетрадь не обходит видимость (D-030)")
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo("message_not_found");
    }

    // --- Веб-слой ----------------------------------------------------------------------

    /**
     * Тетрадь через HTTP: заметка создаётся, читается и сохраняется сообщение. Проверка
     * идёт через `MockMvc`, а не через сервис, потому что именно контроллер связывает
     * сервисы с путями и правами, и незашитый маршрут сервисный тест не поймает.
     */
    @Test
    void noteAndSavedMessageWorkThroughHttp() throws Exception {
        Fixture fixture = runningGame();
        writes.post(fixture.masterUserId, fixture.gameId, fixture.place, fixture.first.characterId,
                MessageKind.SPEECH, "я был на маяке", false);
        UUID messageId = messageIdOf(fixture, "я был на маяке");

        Map<String, Object> noteBody = new java.util.LinkedHashMap<>();
        noteBody.put("targetKind", "CHARACTER");
        noteBody.put("targetId", fixture.first.characterId.toString());
        noteBody.put("body", "заметка через HTTP");
        noteBody.put("keywords", List.of(Map.of("raw", "маяк", "exactMatch", false)));

        var created = call(post("/games/" + fixture.gameId + "/notes"), fixture.first.token, noteBody);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        assertThat(bodyOf(created)).contains("заметка через HTTP").contains("маяк");

        var listed = call(get("/games/" + fixture.gameId + "/notes"), fixture.first.token, null);
        assertThat(listed.getResponse().getStatus()).isEqualTo(200);
        assertThat(bodyOf(listed)).contains("заметка через HTTP");

        var saved = call(
                post("/games/" + fixture.gameId + "/saved-messages"),
                fixture.first.token,
                Map.of("messageId", messageId.toString(), "comment", "важно"));
        assertThat(saved.getResponse().getStatus()).isEqualTo(201);
        assertThat(bodyOf(saved)).contains("важно");

        var mine = call(get("/games/" + fixture.gameId + "/saved-messages"), fixture.first.token, null);
        assertThat(mine.getResponse().getStatus()).isEqualTo(200);
        assertThat(bodyOf(mine)).contains("важно");
    }

    // --- Обвязка -----------------------------------------------------------------------

    /**
     * Только строки-сообщения ленты, в порядке журнала. Системные проекции (`D-031`) отсеяны:
     * у них нет ни тела, ни подсветки, и «первая строка ленты» без этого фильтра оказывалась бы
     * событием перемещения персонажа, а не репликой.
     */
    private List<com.chattergame.message.FeedItem> feedOf(Fixture fixture, Actor reader) {
        return reads.feed(reader.userId, fixture.gameId, fixture.place, reader.characterId, 0, 0)
                .items().stream()
                .filter(item -> item.messageId() != null)
                .toList();
    }

    /** Заметка в текущем состоянии БД: нужна досчёту, который читает признак и цель. */
    private Note noteById(Fixture fixture, UUID noteId) {
        return notes.requireVisibleNote(fixture.masterUserId, fixture.gameId, noteId);
    }

    private UUID messageIdOf(Fixture fixture, String body) {
        return jdbc.queryForObject(
                "SELECT id FROM message WHERE game_id = ? AND body = ?", UUID.class, fixture.gameId, body);
    }

    private final class Fixture {

        final UUID gameId;
        final UUID masterUserId;
        final String masterToken;
        final UUID place;
        final UUID elsewhere;
        final Actor first;
        final Actor second;
        final Actor third;

        private Fixture(
                UUID gameId, UUID masterUserId, String masterToken, UUID place, UUID elsewhere,
                Actor first, Actor second, Actor third) {
            this.gameId = gameId;
            this.masterUserId = masterUserId;
            this.masterToken = masterToken;
            this.place = place;
            this.elsewhere = elsewhere;
            this.first = first;
            this.second = second;
            this.third = third;
        }
    }

    private final class Actor {

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

    /** Игра `RUNNING` с двумя локациями и тремя игроками за своими персонажами. */
    private Fixture runningGame() throws Exception {
        String masterLogin = unique("МастерТетради");
        String masterToken = registerAndGetToken(masterLogin, PASSWORD);
        UUID masterUserId = jdbc.queryForObject(
                "SELECT id FROM app_user WHERE login_normalized = ?",
                UUID.class,
                loginNormalized(masterLogin));

        Game game = mutations.createGame(masterUserId, "Тетрадь " + unique("E12"), null, null);
        UUID place = mutations.createLocation(masterUserId, game.id(), "Площадь", null).id();
        UUID elsewhere = mutations.createLocation(masterUserId, game.id(), "Причал", null).id();
        mutations.applyTransition(masterUserId, game.id(), GameTransition.MARK_READY, null);
        mutations.applyTransition(masterUserId, game.id(), GameTransition.START, null);

        Actor first = actor(game.id(), masterUserId, "Первый");
        Actor second = actor(game.id(), masterUserId, "Второй");
        Actor third = actor(game.id(), masterUserId, "Третий");
        mutations.placeCharacter(masterUserId, game.id(), first.characterId, place);
        mutations.placeCharacter(masterUserId, game.id(), second.characterId, place);
        mutations.placeCharacter(masterUserId, game.id(), third.characterId, elsewhere);

        return new Fixture(game.id(), masterUserId, masterToken, place, elsewhere, first, second, third);
    }

    private Actor actor(UUID gameId, UUID masterUserId, String prefix) throws Exception {
        String login = unique(prefix);
        register(login, OTHER_PASSWORD, null);
        UUID userId = jdbc.queryForObject(
                "SELECT id FROM app_user WHERE login_normalized = ?",
                UUID.class,
                loginNormalized(login));
        Membership membership = mutations.addMembership(
                masterUserId, gameId, userId, MembershipRole.PLAYER, MembershipStatus.JOINED);
        Character character = mutations.createCharacter(
                masterUserId, gameId, prefix + "-" + unique(""), null, null, null, null, true, false);
        jdbc.update("UPDATE character SET occupant_membership_id = ? WHERE id = ?",
                membership.id(), character.id());
        return new Actor(userId, membership.id(), character.id(),
                loginAndGetToken(login, OTHER_PASSWORD));
    }

    private Actor observer(Fixture fixture) throws Exception {
        String login = unique("НаблюдательТетради");
        register(login, OTHER_PASSWORD, null);
        UUID userId = jdbc.queryForObject(
                "SELECT id FROM app_user WHERE login_normalized = ?",
                UUID.class,
                loginNormalized(login));
        Membership membership = mutations.addMembership(
                fixture.masterUserId, fixture.gameId, userId,
                MembershipRole.OBSERVER, MembershipStatus.JOINED);
        return new Actor(userId, membership.id(), null, loginAndGetToken(login, OTHER_PASSWORD));
    }
}
