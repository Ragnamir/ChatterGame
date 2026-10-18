package com.chattergame.web.game;

import com.chattergame.export.GameExportService;
import com.chattergame.export.GameSnapshotRestorer;
import com.chattergame.web.AuthContext;
import com.chattergame.web.AuthContextResolver;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * Экспорт и восстановление игры (`D-061`, `D-146`).
 *
 * <p>Два маршрута на выгрузку, а не один с параметром формата: JSON и Markdown — разные
 * получатели (машина и человек), и браузеру нужен разный `Content-Type`, чтобы файл
 * открывался, а не скачивался текстом.
 *
 * <p>Право мастера проверяет сервис: тот же снимок придёт из скрипта восстановления, и
 * условие обязано быть одно.
 */
@RestController
@RequestMapping("/games")
public class GameExportController {

    private final GameExportService exports;
    private final GameSnapshotRestorer restorer;

    public GameExportController(GameExportService exports, GameSnapshotRestorer restorer) {
        this.exports = exports;
        this.restorer = restorer;
    }

    @GetMapping("/{id}/export.json")
    public ResponseEntity<byte[]> json(@PathVariable UUID id, AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        GameExportService.Export export = exports.export(auth.user().id(), id);
        return download(export.fileBase() + ".json", MediaType.APPLICATION_JSON, export.json());
    }

    @GetMapping("/{id}/export.md")
    public ResponseEntity<byte[]> markdown(@PathVariable UUID id, AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        GameExportService.Export export = exports.export(auth.user().id(), id);
        return download(export.fileBase() + ".md", MediaType.parseMediaType("text/markdown"), export.markdown());
    }

    /**
     * Восстановление снимка. По умолчанию идентификатор игры берётся из файла: снимок — это
     * игра целиком, и «залить его в другую игру» означало бы переписать чужую историю.
     * `asNewGame` нужен для восстановления рядом с оригиналом — например, когда проверяют
     * бэкап на живой базе.
     */
    @PostMapping("/import")
    public ResponseEntity<Map<String, Object>> restore(
            @RequestBody Map<String, Object> document,
            @RequestParam(defaultValue = "false") boolean asNewGame,
            AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        UUID gameId = restorer.restore(auth.user().id(), document, asNewGame);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("gameId", gameId.toString()));
    }

    private static ResponseEntity<byte[]> download(String fileName, MediaType type, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(fileName, StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(new MediaType(type, StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(bytes);
    }
}
