package com.chattergame.note;

import com.chattergame.character.CharacterRepository;
import com.chattergame.game.Game;
import com.chattergame.game.GameQueryService;
import com.chattergame.game.Membership;
import com.chattergame.game.MembershipRepository;
import com.chattergame.message.InboxSubject;
import com.chattergame.util.ApiException;
import com.chattergame.util.Uuids;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Сохранённые сообщения тетради (`REQUIREMENTS §15.2`, `PLAN` E12, задачи 7–8).
 *
 * <p>Привязка — к участию, а не к персонажу (`D-055`): это мета-инструмент игрока, и при
 * смене персонажа записи остаются. Поэтому владелец — всегда `MEMBERSHIP`, а не персонаж,
 * и наблюдатель пользуется тетрадью наравне с игроком: у него персонажа нет, но participation
 * есть (`D-063`). Заметок у наблюдателя при этом нет вовсе — это разные вещи, и решение
 * разводит их сознательно.
 *
 * <p>Снимок текста и имени говорящего берётся в момент сохранения (`D-055`): правка карточки
 * мастером не должна менять чужие записи задним числом. Сохранить можно только то, что
 * субъекту выдали в ленте: снимок ищется по `inbox_entry` (`D-030`), иначе тетрадь стала бы
 * обходным путём к чужой переписке.
 */
@Service
public class SavedMessageService {

    private final NoteRepository notes;
    private final GameQueryService games;
    private final MembershipRepository memberships;
    private final CharacterRepository characters;
    private final NoteService noteService;

    public SavedMessageService(
            NoteRepository notes,
            GameQueryService games,
            MembershipRepository memberships,
            CharacterRepository characters,
            NoteService noteService) {
        this.notes = notes;
        this.games = games;
        this.memberships = memberships;
        this.characters = characters;
        this.noteService = noteService;
    }

    /**
     * Сохранение сообщения в тетрадь. Копится один раз на участие: повтор того же сообщения
     * не создаёт второй записи, а уточняет комментарий, потому что «сохранить дважды» — это
     * тот же жест, а не второе намерение.
     */
    @Transactional
    public SavedMessage save(UUID userId, UUID gameId, SaveRequest request) {
        Game game = games.requireReadable(userId, gameId);
        Membership owner = membership(game, userId);
        if (request.messageId() == null) {
            throw ApiException.badRequest("message_required", "Укажите сообщение");
        }
        NoteRepository.Snapshot snapshot = notes
                .snapshotFor(readers(owner), request.messageId())
                .orElseThrow(() -> ApiException.notFound(
                        "message_not_found", "Сообщение не найдено в доступной ленте"));
        UUID noteId = null;
        if (request.noteId() != null) {
            noteService.requireVisibleNote(userId, gameId, request.noteId());
            noteId = request.noteId();
        }
        String comment = requireComment(request.comment());
        SavedMessage existing =
                notes.findSavedByOwnerAndMessage(owner.id(), request.messageId()).orElse(null);
        if (existing != null) {
            notes.updateSavedComment(existing.id(), comment);
            return new SavedMessage(
                    existing.id(),
                    existing.gameId(),
                    existing.ownerMembershipId(),
                    existing.messageId(),
                    existing.textSnapshot(),
                    existing.speakerSnapshot(),
                    comment,
                    noteId == null ? existing.noteId() : noteId,
                    existing.createdAt());
        }
        SavedMessage saved = new SavedMessage(
                Uuids.v7(),
                game.id(),
                owner.id(),
                request.messageId(),
                snapshot.text(),
                snapshot.speaker(),
                comment,
                noteId,
                Instant.now());
        notes.insertSaved(saved);
        return saved;
    }

    /**
     * Тетрадь смотрящего. Видит только свои записи: сохранённое сообщение — мета-инструмент
     * игрока (`D-055`), и чужая выборка была бы подсматриванием, а не тетрадью.
     */
    @Transactional(readOnly = true)
    public List<SavedMessage> mine(UUID userId, UUID gameId) {
        Game game = games.requireReadable(userId, gameId);
        Membership owner = membership(game, userId);
        return notes.savedByOwner(owner.id());
    }

    /** Правка комментария своей записи. Текст берётся из уже снятого снимка (`D-055`). */
    @Transactional
    public SavedMessage comment(UUID userId, UUID gameId, UUID savedId, String comment) {
        Game game = games.requireReadable(userId, gameId);
        Membership owner = membership(game, userId);
        SavedMessage saved = notes.findSaved(savedId)
                .orElseThrow(() -> ApiException.notFound("saved_not_found", "Запись не найдена"));
        if (!saved.ownerMembershipId().equals(owner.id())) {
            throw ApiException.forbidden("not_saved_owner", "Запись принадлежит другому игроку");
        }
        notes.updateSavedComment(savedId, requireComment(comment));
        return new SavedMessage(
                saved.id(),
                saved.gameId(),
                saved.ownerMembershipId(),
                saved.messageId(),
                saved.textSnapshot(),
                saved.speakerSnapshot(),
                requireComment(comment),
                saved.noteId(),
                saved.createdAt());
    }

    private static String requireComment(String comment) {
        if (comment == null) {
            return null;
        }
        String stripped = comment.strip();
        if (stripped.length() > SavedMessage.COMMENT_MAX) {
            throw ApiException.badRequest(
                    "comment_too_long", "Комментарий до " + SavedMessage.COMMENT_MAX + " знаков");
        }
        return stripped.isEmpty() ? null : stripped;
    }

    /**
     * Субъекты, которыми смотрящий читает ленту (`D-030`): своё участие и персонажи, за
     * которых он играет. Наблюдатель читает участием (`D-063`), игрок — персонажем, поэтому
     * набор, а не один субъект: иначе игрок не смог бы сохранить реплику, адресованную
     * его же персонажу.
     */
    private List<InboxSubject> readers(Membership viewer) {
        List<InboxSubject> subjects = new java.util.ArrayList<>();
        subjects.add(InboxSubject.membership(viewer.id()));
        for (var character : characters.findByOccupant(viewer.id())) {
            subjects.add(InboxSubject.character(character.id()));
        }
        return subjects;
    }

    private Membership membership(Game game, UUID userId) {
        return memberships.find(game.id(), userId)
                .orElseThrow(() -> ApiException.forbidden("not_a_member", "Нет доступа к этой игре"));
    }

    /** Запрос сохранения: сообщение, необязательный комментарий и необязательная заметка. */
    public record SaveRequest(UUID messageId, String comment, UUID noteId) {
    }
}
