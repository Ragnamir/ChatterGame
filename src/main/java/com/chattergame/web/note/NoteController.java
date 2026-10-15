package com.chattergame.web.note;

import com.chattergame.note.NoteService;
import com.chattergame.note.SavedMessageService;
import com.chattergame.web.AuthContext;
import com.chattergame.web.AuthContextResolver;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Тетрадь: заметки и сохранённые сообщения (`PLAN` E12).
 *
 * <p>Контроллер только передаёт вызов сервису: доменных таблиц он не касается, а мутации
 * идут через {@link NoteService} и {@link SavedMessageService}. Тетрадь — мета-слой игрока,
 * и в журнал событий она не пишет: заметка не меняет того, что видят другие (`D-055`).
 *
 * <p>Видимость разведена сознательно: заметки — мастеру и своим персонажам, наблюдателю
 * нет вовсе (`D-054`, `D-063`), сохранённые сообщения — любому участию, включая наблюдателя.
 * Это следствие решения, а не недосмотр: тетрадь принадлежит игроку, заметка — персонажу.
 */
@RestController
@RequestMapping("/games/{gameId}")
public class NoteController {

    private final NoteService notes;
    private final SavedMessageService saved;

    public NoteController(NoteService notes, SavedMessageService saved) {
        this.notes = notes;
        this.saved = saved;
    }

    // --- Заметки ---------------------------------------------------------------------

    @GetMapping("/notes")
    public List<NoteView> list(@PathVariable UUID gameId, AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        return notes.visible(auth.user().id(), gameId).stream().map(NoteView::of).toList();
    }

    @PostMapping("/notes")
    public ResponseEntity<NoteView> create(
            @PathVariable UUID gameId,
            @Valid @RequestBody NoteRequest request,
            AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        return ResponseEntity.status(HttpStatus.CREATED).body(NoteView.of(
                notes.create(auth.user().id(), gameId, draft(request))));
    }

    @PutMapping("/notes/{noteId}")
    public NoteView update(
            @PathVariable UUID gameId,
            @PathVariable UUID noteId,
            @Valid @RequestBody NoteRequest request,
            AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        return NoteView.of(notes.update(auth.user().id(), gameId, noteId, draft(request)));
    }

    // --- Сохранённые сообщения ---------------------------------------------------------

    @GetMapping("/saved-messages")
    public List<SavedMessageView> savedList(@PathVariable UUID gameId, AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        return saved.mine(auth.user().id(), gameId).stream().map(SavedMessageView::of).toList();
    }

    @PostMapping("/saved-messages")
    public ResponseEntity<SavedMessageView> save(
            @PathVariable UUID gameId,
            @Valid @RequestBody SaveMessageRequest request,
            AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        return ResponseEntity.status(HttpStatus.CREATED).body(SavedMessageView.of(saved.save(
                auth.user().id(),
                gameId,
                new SavedMessageService.SaveRequest(
                        request.messageId(), request.comment(), request.noteId()))));
    }

    @PutMapping("/saved-messages/{savedId}")
    public SavedMessageView comment(
            @PathVariable UUID gameId,
            @PathVariable UUID savedId,
            @Valid @RequestBody CommentRequest request,
            AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        return SavedMessageView.of(
                saved.comment(auth.user().id(), gameId, savedId, request.comment()));
    }

    private static NoteService.Draft draft(NoteRequest request) {
        return new NoteService.Draft(
                request.targetKind(),
                request.targetId(),
                request.body(),
                request.keywords() == null
                        ? List.of()
                        : request.keywords().stream()
                                .map(keyword -> new NoteService.Draft.Keyword(
                                        keyword.raw(), keyword.exactMatch()))
                                .toList());
    }
}
