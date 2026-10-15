package com.chattergame.note;

import com.chattergame.character.Character;
import com.chattergame.character.CharacterRepository;
import com.chattergame.game.Game;
import com.chattergame.game.GameQueryService;
import com.chattergame.game.Membership;
import com.chattergame.game.MembershipRepository;
import com.chattergame.game.MembershipRole;
import com.chattergame.message.InboxSubject;
import com.chattergame.message.SubjectKind;
import com.chattergame.util.ApiException;
import com.chattergame.util.Uuids;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Заметки тетради (`REQUIREMENTS §15.1`, `PLAN` E12, задачи 2–6).
 *
 * <p>Заметка — мета-инструмент, а не объект мира: она не меняет того, что видят другие
 * игроки, и в журнале событий её нет. Тетрадь намеренно отделена от мира решением `D-055`:
 * сохранённое сообщение принадлежит игроку и переживает смену персонажа, заметка
 * принадлежит персонажу и подсвечивает его ленту. Поэтому здесь нет ни рассылки, ни
 * `GameMutationService`: журнал — источник истины для того, что видно всем, а тетрадь
 * видна только своей стороне.
 *
 * <p>Совпадение ключей при записи сообщения считает {@link NoteMatchingService} (`D-050`),
 * старые сообщения досчитывает {@link NoteBackfillService} (`D-087`); сама заметка только
 * поднимает `matching_pending`, чтобы клиент показал «подсветка готовится», а не пустой
 * список — молчаливый пустой результат выглядит как сломанная фича.
 */
@Service
public class NoteService {

    private final NoteRepository notes;
    private final CharacterRepository characters;
    private final MembershipRepository memberships;
    private final GameQueryService games;
    private final NoteBackfillExecutor backfill;

    public NoteService(
            NoteRepository notes,
            CharacterRepository characters,
            MembershipRepository memberships,
            GameQueryService games,
            NoteBackfillExecutor backfill) {
        this.notes = notes;
        this.characters = characters;
        this.memberships = memberships;
        this.games = games;
        this.backfill = backfill;
    }

    /**
     * Создание заметки. Мастер пишет любому персонажу игры и себе; игрок — только своим
     * персонажам (`§15.1`). Наблюдателю заметок нет вовсе (`D-063`): у него нет персонажа,
     * а заметка на участие не адресуется.
     */
    @Transactional
    public Note.WithKeywords create(UUID actorUserId, UUID gameId, Draft draft) {
        Game game = games.requireReadable(actorUserId, gameId);
        Membership actor = membership(game, actorUserId);
        requireNotObserver(actor);
        InboxSubject target = resolveTarget(game, actor, draft.targetKind(), draft.targetId());
        String body = requireBody(draft.body());
        List<NoteKeyword> keywords = keywordDrafts(draft.keywords());

        Instant now = Instant.now();
        Note note = Note.of(
                Uuids.v7(),
                game.id(),
                actor.id(),
                target,
                body,
                !keywords.isEmpty(),
                now,
                now);
        notes.insert(note);
        for (NoteKeyword keyword : keywords) {
            notes.insertKeyword(new NoteKeyword(
                    Uuids.v7(), note.id(), keyword.raw(), keyword.normalized(), keyword.exactMatch()));
        }
        scheduleBackfill();
        return new Note.WithKeywords(note, keywords, labelOf(target));
    }

    /**
     * Правка заметки. Правит только автор: мастер видит заметки игроков (`D-054`), но это
     * чтение, а не право переписать чужое замечание. Цель правкой не меняется — иначе
     * заметка переезжала бы на другого персонажа, унося с собой подсветку.
     */
    @Transactional
    public Note.WithKeywords update(UUID actorUserId, UUID gameId, UUID noteId, Draft draft) {
        Game game = games.requireReadable(actorUserId, gameId);
        Membership actor = membership(game, actorUserId);
        requireNotObserver(actor);
        Note note = requireNote(gameId, noteId);
        if (!note.authorMembershipId().equals(actor.id())) {
            throw ApiException.forbidden("not_note_author", "Править заметку может только её автор");
        }
        String body = requireBody(draft.body());
        List<NoteKeyword> keywords = keywordDrafts(draft.keywords());

        Instant now = Instant.now();
        // Правка ключей — та же мутация, что и создание: сначала состав ключей, потом
        // признак досчёта. Обратный порядок оставил бы окно, где флаг снят, а ключи старые.
        // Признак поднимается при любой правке, даже если ключи стали пустыми: досчёт
        // обязан не только добавить новые подсветки, но и снять снятые ключи.
        notes.replaceKeywords(note.id(), withFreshIds(note.id(), keywords));
        notes.updateBody(note.id(), body, true, now);

        Note updated = Note.of(
                note.id(),
                note.gameId(),
                note.authorMembershipId(),
                note.target(),
                body,
                true,
                note.createdAt(),
                now);
        scheduleBackfill();
        return new Note.WithKeywords(updated, notes.keywordsOf(note.id()), labelOf(note.target()));
    }

    /**
     * Досчёт ставится после коммита: асинхронный поток обязан видеть уже записанную заметку
     * и её ключи, а не строки, которых ещё нет в БД. Регистрация в текущей транзакции —
     * единственный момент, когда «заметка точно сохранена» известно точно.
     */
    private void scheduleBackfill() {
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        backfill.schedule();
                    }
                });
    }

    /**
     * Заметки, видимые смотрящему. Мастер видит всё (`D-054`), игрок — свои и за своих
     * персонажей, наблюдатель — ничего (`D-063`). Маркер владельца (`D-053`) — цель заметки
     * вместе с её подписью (`D-127`): у игрока с несколькими персонажами заметки приходят
     * списком, и одним идентификатором цели клиент отличил бы их только вторым запросом
     * за именами.
     */
    @Transactional(readOnly = true)
    public List<Note.WithKeywords> visible(UUID userId, UUID gameId) {
        Game game = games.requireReadable(userId, gameId);
        Membership viewer = membership(game, userId);
        if (viewer.role() == MembershipRole.OBSERVER) {
            return List.of();
        }
        Map<UUID, Note> gathered = new LinkedHashMap<>();
        if (viewer.role() == MembershipRole.MASTER) {
            for (Note note : notes.findByGame(game.id())) {
                gathered.putIfAbsent(note.id(), note);
            }
        } else {
            for (Note note : notes.findByAuthor(game.id(), viewer.id())) {
                gathered.putIfAbsent(note.id(), note);
            }
            for (Character character : characters.findByOccupant(viewer.id())) {
                for (Note note : notes.findByTarget(game.id(), InboxSubject.character(character.id()))) {
                    gathered.putIfAbsent(note.id(), note);
                }
            }
        }
        Map<UUID, List<NoteKeyword>> index = new LinkedHashMap<>();
        for (UUID noteId : gathered.keySet()) {
            index.put(noteId, notes.keywordsOf(noteId));
        }
        List<Note.WithKeywords> result = new ArrayList<>();
        for (Note note : gathered.values()) {
            result.add(new Note.WithKeywords(
                    note, index.get(note.id()), labelOf(note.target())));
        }
        return result;
    }

    /** Заметка, видимая смотрящему. Нужна сохранённому сообщению, которое ссылается на заметку. */
    @Transactional(readOnly = true)
    public Note requireVisibleNote(UUID userId, UUID gameId, UUID noteId) {
        Note note = requireNote(gameId, noteId);
        Membership viewer = membership(games.requireReadable(userId, gameId), userId);
        if (viewer.role() == MembershipRole.OBSERVER) {
            throw ApiException.forbidden("notes_not_for_observer", "Наблюдателю заметок нет");
        }
        if (viewer.role() == MembershipRole.MASTER || note.authorMembershipId().equals(viewer.id())) {
            return note;
        }
        if (note.target().kind() == SubjectKind.CHARACTER
                && occupies(viewer, note.target().id())) {
            return note;
        }
        throw ApiException.notFound("note_not_found", "Заметка не найдена");
    }

    /** Все заметки игры: предмет решения `D-054`, используется экспортом и мастерской панелью. */
    @Transactional(readOnly = true)
    public List<Note> allOf(UUID gameId) {
        return notes.findByGame(gameId);
    }

    public List<NoteKeyword> keywordsOf(UUID noteId) {
        return notes.keywordsOf(noteId);
    }

    private boolean occupies(Membership viewer, UUID characterId) {
        return characters.findById(characterId)
                .map(character -> viewer.id().equals(character.occupantMembershipId()))
                .orElse(false);
    }

    /**
     * Цель заметки. Мастер целится в любого персонажа игры или в себя (участие), игрок —
     * только в своего персонажа: чужой персонаж — не его знание (`§15.1`).
     */
    /**
     * Подпись цели для маркера владельца (`D-053`, `D-127`). Имя персонажа берётся живым:
     * правка карточки мастером меняет подпись задним числом, и это принято сознательно —
     * подпись отличает замечания друг от друга, а не фиксирует исторический факт, в отличие
     * от снимка говорящего у сохранённого сообщения (`D-055`). Для цели-участия подписи нет:
     * это заметка мастера о себе, и подставлять туда чужое имя неоткуда.
     */
    private String labelOf(InboxSubject target) {
        if (target.kind() != SubjectKind.CHARACTER) {
            return null;
        }
        return characters.findById(target.id()).map(Character::name).orElse(null);
    }

    private InboxSubject resolveTarget(Game game, Membership actor, SubjectKind kind, UUID targetId) {
        if (kind == null || targetId == null) {
            throw ApiException.badRequest("note_target_required", "Укажите цель заметки");
        }
        if (kind == SubjectKind.MEMBERSHIP) {
            if (actor.role() != MembershipRole.MASTER) {
                throw ApiException.forbidden(
                        "note_target_not_allowed", "Игрок заводит заметки только для своих персонажей");
            }
            if (!actor.id().equals(targetId)) {
                throw ApiException.forbidden(
                        "note_target_not_allowed", "Мастер заводит заметку для себя только себе");
            }
            return InboxSubject.membership(actor.id());
        }
        Character character = characters.findById(targetId)
                .orElseThrow(() -> ApiException.notFound("character_not_found", "Персонаж не найден"));
        if (!character.gameId().equals(game.id())) {
            throw ApiException.notFound("character_not_found", "Персонаж не найден");
        }
        if (actor.role() != MembershipRole.MASTER && !actor.id().equals(character.occupantMembershipId())) {
            throw ApiException.forbidden(
                    "note_target_not_allowed", "Игрок заводит заметки только для своих персонажей");
        }
        return InboxSubject.character(character.id());
    }

    private String requireBody(String body) {
        if (body == null || body.isBlank()) {
            throw ApiException.badRequest("note_body_required", "Заметка не может быть пустой");
        }
        if (body.strip().length() > Note.BODY_MAX) {
            throw ApiException.badRequest(
                    "note_body_too_long", "Заметка до " + Note.BODY_MAX + " знаков");
        }
        return body.strip();
    }

    private List<NoteKeyword> keywordDrafts(List<Draft.Keyword> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            return List.of();
        }
        if (drafts.size() > Note.KEYWORDS_MAX) {
            throw ApiException.badRequest(
                    "note_keywords_too_many", "Ключевых слов не больше " + Note.KEYWORDS_MAX);
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<NoteKeyword> result = new ArrayList<>();
        for (Draft.Keyword draft : drafts) {
            if (draft == null || draft.raw() == null || draft.raw().isBlank()) {
                throw ApiException.badRequest("note_keyword_required", "Пустое ключевое слово");
            }
            String raw = draft.raw().strip();
            if (raw.length() > KeywordMatcher.MAX_KEYWORD_LENGTH) {
                throw ApiException.badRequest(
                        "note_keyword_too_long",
                        "Ключ до " + KeywordMatcher.MAX_KEYWORD_LENGTH + " знаков");
            }
            String normalized = KeywordMatcher.storedForm(raw, draft.exactMatch());
            // Дубль в одной заметке — это опечатка ввода, а не конфликт ключей (`D-052`):
            // конфликт — две разные заметки на одно слово, и он разрешается списком.
            if (!seen.add(normalized + "|" + draft.exactMatch())) {
                continue;
            }
            result.add(new NoteKeyword(null, null, raw, normalized, draft.exactMatch()));
        }
        return result;
    }

    private static List<NoteKeyword> withFreshIds(UUID noteId, List<NoteKeyword> keywords) {
        List<NoteKeyword> result = new ArrayList<>(keywords.size());
        for (NoteKeyword keyword : keywords) {
            result.add(new NoteKeyword(
                    Uuids.v7(),
                    noteId,
                    keyword.raw(),
                    keyword.normalized(),
                    keyword.exactMatch()));
        }
        return result;
    }

    private Note requireNote(UUID gameId, UUID noteId) {
        Note note = notes.findById(noteId)
                .orElseThrow(() -> ApiException.notFound("note_not_found", "Заметка не найдена"));
        if (!note.gameId().equals(gameId)) {
            throw ApiException.notFound("note_not_found", "Заметка не найдена");
        }
        return note;
    }

    private Membership membership(Game game, UUID userId) {
        return memberships.find(game.id(), userId)
                .orElseThrow(() -> ApiException.forbidden("not_a_member", "Нет доступа к этой игре"));
    }

    private static void requireNotObserver(Membership actor) {
        if (actor.role() == MembershipRole.OBSERVER) {
            throw ApiException.forbidden("notes_not_for_observer", "Наблюдателю заметок нет");
        }
    }

    /** Запрос заметки: цель, текст и ключи. Ключ — пара «слово + режим точного совпадения». */
    public record Draft(SubjectKind targetKind, UUID targetId, String body, List<Keyword> keywords) {

        public record Keyword(String raw, boolean exactMatch) {
        }
    }
}
