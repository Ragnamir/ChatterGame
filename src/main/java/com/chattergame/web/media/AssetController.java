package com.chattergame.web.media;

import com.chattergame.media.Asset;
import com.chattergame.media.AssetService;
import com.chattergame.web.AuthContext;
import com.chattergame.web.AuthContextResolver;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.util.UUID;

/**
 * Приём и отдача медиа (`PLAN` E05). Контроллер не решает про формат и лимиты: он передаёт
 * байты в {@link AssetService}, иначе проверки размножились бы по эндпоинтам и разошлись.
 *
 * <p>Отдача — из приложения, а не веб-сервером (`REQUIREMENTS §19`): только так проверка
 * участия (`D-075`) стоит на каждом запросе. Заголовки выставлены явно: `nosniff` не даёт
 * браузеру переугадать тип, точный `Content-Type` берётся из сохранённого, долгий
 * `Cache-Control` возможен потому, что идентификатор ассета неперечислим и неизменяем
 * (`D-077`).
 */
@RestController
@RequestMapping("/games/{gameId}/assets")
public class AssetController {

    /** Долгий кэш: идентификатор ассета неперечислим и содержимое по нему не меняется (`D-077`). */
    private static final String CACHE_CONTROL = "private, max-age=31536000, immutable";

    private final AssetService assets;

    public AssetController(AssetService assets) {
        this.assets = assets;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AssetView> upload(
            @PathVariable UUID gameId,
            @RequestPart("file") MultipartFile file,
            AuthContext context) throws IOException {
        AuthContext auth = AuthContextResolver.require(context);
        Asset asset = assets.upload(
                auth.user().id(), gameId, file.getOriginalFilename(), file.getBytes());
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/games/" + gameId + "/assets/" + asset.id()))
                .body(AssetView.of(asset));
    }

    @GetMapping("/{assetId}")
    public ResponseEntity<byte[]> content(
            @PathVariable UUID gameId, @PathVariable UUID assetId, AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        Asset asset = assets.loadForRead(auth.user().id(), gameId, assetId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(asset.mime()))
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .header("X-Content-Type-Options", "nosniff")
                .body(assets.content(asset));
    }
}
