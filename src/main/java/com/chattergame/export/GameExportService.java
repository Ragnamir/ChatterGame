package com.chattergame.export;

import com.chattergame.game.Game;
import com.chattergame.game.GameRepository;
import com.chattergame.util.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Экспорт полного комплекта игры (`D-061`, `D-146`).
 *
 * <p>Выгружает мастер и только он: комплект содержит личные каналы, закрытые истории и
 * заметки, а передача прав в проекте не предусмотрена (`D-021`). Мастер решает, отдать ли
 * файл заменителю, и архитектура этого решения не принимает.
 *
 * <p>Экспорт не зависит от фазы игры: он читает, а не меняет, и после `FINISHED` остаётся
 * единственным способом забрать материал (`D-094`).
 */
@Service
public class GameExportService {

    /** Имя формата в документе: по нему восстановление отличает свой файл от чужого JSON. */
    public static final String FORMAT = "chattergame-game-snapshot";

    /** Версия формата. Меняется, когда меняется состав или смысл таблиц снимка. */
    public static final int VERSION = 1;

    private final GameRepository games;
    private final GameSnapshotReader reader;
    private final GameMarkdownRenderer renderer;
    private final ObjectMapper json;

    public GameExportService(
            GameRepository games,
            GameSnapshotReader reader,
            GameMarkdownRenderer renderer,
            ObjectMapper json) {
        this.games = games;
        this.reader = reader;
        this.renderer = renderer;
        this.json = json;
    }

    /**
     * Комплект игры: JSON для машины и Markdown для человека. Обе части строятся из одного
     * снимка, поэтому не могут разойтись по составу.
     */
    @Transactional(readOnly = true)
    public Export export(UUID actorUserId, UUID gameId) {
        Game game = requireMaster(actorUserId, gameId);
        Map<String, List<Map<String, Object>>> tables = reader.read(gameId);

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("format", FORMAT);
        document.put("version", VERSION);
        document.put("exportedAt", Instant.now().toString());
        document.put("gameId", gameId.toString());
        document.put("title", game.title());
        document.put("closed", closedMarking(tables));
        document.put("tables", tables);

        return new Export(
                write(document),
                renderer.render(document),
                fileBase(game, gameId));
    }

    /**
     * Что в файле закрыто. Машине это нужно не меньше человека: получатель не обязан читать
     * Markdown, чтобы узнать, что перед ним материал не для игроков.
     */
    private Map<String, Object> closedMarking(Map<String, List<Map<String, Object>>> tables) {
        Map<String, Object> closed = new LinkedHashMap<>();
        closed.put("notice", "Файл содержит закрытые материалы игры и предназначен только мастеру (D-061)");
        closed.put("gameFields", List.of("private_description"));
        closed.put("chatIds", tables.get("chat").stream()
                .filter(row -> !"LOCATION".equals(row.get("kind")) && !"FINAL".equals(row.get("kind")))
                .map(row -> row.get("id"))
                .toList());
        List<Map<String, Object>> histories = new ArrayList<>();
        for (Map<String, Object> character : tables.get("character")) {
            if (character.get("private_history") != null) {
                histories.add(Map.of("id", character.get("id"), "field", "private_history"));
            }
        }
        closed.put("characterHistories", histories);
        closed.put("noteIds", tables.get("note").stream().map(row -> row.get("id")).toList());
        return closed;
    }

    private String write(Map<String, Object> document) {
        try {
            return json.writerWithDefaultPrettyPrinter().writeValueAsString(document);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Не удалось собрать снимок игры", e);
        }
    }

    private static String fileBase(Game game, UUID gameId) {
        String title = game.title() == null ? "game" : game.title();
        String safe = title.replaceAll("[^\\p{L}\\p{N}]+", "-").replaceAll("(^-|-$)", "");
        if (safe.isBlank()) {
            safe = "game";
        }
        return safe + "-" + gameId;
    }

    private Game requireMaster(UUID actorUserId, UUID gameId) {
        Game game = games.findById(gameId)
                .orElseThrow(() -> ApiException.notFound("game_not_found", "Игра не найдена"));
        if (!game.masterUserId().equals(actorUserId)) {
            throw ApiException.forbidden(
                    "export_master_only", "Экспорт доступен только мастеру игры");
        }
        return game;
    }

    /** Готовый комплект: тело ответа и основа имени файла. */
    public record Export(String json, String markdown, String fileBase) {
    }
}
