package com.chattergame.media;

import com.chattergame.game.Game;
import com.chattergame.game.GameRepository;
import com.chattergame.game.Membership;
import com.chattergame.game.MembershipRepository;
import com.chattergame.game.MembershipRole;
import com.chattergame.util.ApiException;
import com.chattergame.util.Uuids;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Приём и отдача медиа (`PLAN` E05).
 *
 * <p>Порядок проверок в {@link #upload} задан дешевизной и обратимостью: сначала то, что
 * не требует памяти (размер, затем формат по магическим байтам), затем стороны картинки по
 * заголовку, и только потом диск и БД. Ни на одной ветке отказа файл не записывается вовсе,
 * поэтому «отказ оставил мусор» невозможно по построению.
 *
 * <p>Загрузка не пишет событие в журнал: ассет, на который никто не ссылается, — не часть
 * игрового мира. Мир меняется тогда, когда картинка привязывается к локации, и это уже
 * мутация через `GameMutationService` (`D-034`). Ровно поэтому SQL по `asset` живёт
 * в репозитории, а не здесь: сервисный слой не содержит ни одного доменного оператора.
 *
 * <p>Лимит одновременных загрузок — в памяти процесса, а не в БД: он про нагрузку на этот
 * воркер, а не про состояние игры. При нескольких инстансах (`D-072`) предел получится
 * кратен числу инстансов, и это приемлемо: цель — не дать одному клиенту занять все потоки.
 */
@Service
public class AssetService {

    private final AssetRepository assets;
    private final AssetStore store;
    private final MediaSettings settings;
    private final GameRepository games;
    private final MembershipRepository memberships;

    /** Загрузки «в полёте» по пользователю; ключ — пользователь, а не игра. */
    private final Map<UUID, AtomicInteger> inFlight = new ConcurrentHashMap<>();

    public AssetService(
            AssetRepository assets,
            AssetStore store,
            MediaSettings settings,
            GameRepository games,
            MembershipRepository memberships) {
        this.assets = assets;
        this.store = store;
        this.settings = settings;
        this.games = games;
        this.memberships = memberships;
    }

    /**
     * Приём файла. Портрет, картинка локации и всё остальное загружаются одинаково: разница
     * появляется на привязке, а не здесь, иначе проверки формата размножились бы по эндпоинтам.
     */
    @Transactional
    public Asset upload(UUID actorUserId, UUID gameId, String originalName, byte[] content) {
        Game game = requireMaster(actorUserId, gameId);
        if (content == null || content.length == 0) {
            throw ApiException.badRequest("asset_empty", "Файл пуст");
        }
        if (content.length > settings.maxFileBytes()) {
            throw ApiException.badRequest(
                    "asset_too_large", "Файл больше " + settings.maxFileBytes() + " байт");
        }

        ImageFormat format = ImageFormat.detect(content);
        ImageProbe.Dimensions size = ImageProbe.dimensions(content, format);
        if (size.width() > settings.maxSidePixels() || size.height() > settings.maxSidePixels()) {
            throw ApiException.badRequest(
                    "asset_too_many_pixels",
                    "Сторона изображения больше " + settings.maxSidePixels() + " пикселей");
        }

        byte[] stripped = MetadataStripper.strip(content, format);
        requireQuotaLeft(game.id(), stripped.length);
        return withUploadSlot(
                actorUserId, () -> persist(game, actorUserId, originalName, stripped, format, size));
    }

    /**
     * Ассет для отдачи. Право проверяется на каждом запросе, а не при открытии соединения
     * (`D-065`), и отказ неотличим от несуществующего ассета: посторонний получает `404`,
     * а не `403`, иначе он узнал бы о существовании чужой игры по коду ответа (`D-075`).
     *
     * <p>Игра в пути обязана совпадать с игрой ассета: иначе участник одной игры вычитал бы
     * картинки другой, зная только идентификатор ассета.
     */
    @Transactional(readOnly = true)
    public Asset loadForRead(UUID actorUserId, UUID gameId, UUID assetId) {
        Asset asset = requireAsset(assetId);
        if (!asset.gameId().equals(gameId)) {
            throw assetNotFound();
        }
        boolean participant = memberships.find(gameId, actorUserId)
                .filter(membership -> !membership.banned())
                .isPresent();
        if (!participant) {
            throw assetNotFound();
        }
        return asset;
    }

    public byte[] content(Asset asset) {
        return store.read(asset.storageKey());
    }

    public MediaSettings settings() {
        return settings;
    }

    /**
     * Снимает ассет, если на него больше никто не ссылается. Вызывается при замене картинки
     * и при её снятии: `D-077` требует «переключить указатель, удалить старый», иначе диск
     * занимается замещёнными файлами навсегда.
     *
     * <p>`MANDATORY`: удаление обязано попасть в ту же транзакцию, что и переключение
     * указателя. Иначе при откате транзакции картинка осталась бы в старой ссылке, а файл
     * был бы уже удалён.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseIfUnreferenced(UUID assetId) {
        if (assetId == null || assets.isReferenced(assetId)) {
            return;
        }
        assets.findById(assetId).ifPresent(asset -> {
            assets.purge(assetId);
            store.delete(asset.storageKey());
        });
    }

    private Asset persist(
            Game game,
            UUID actorUserId,
            String originalName,
            byte[] content,
            ImageFormat format,
            ImageProbe.Dimensions size) {
        UUID assetId = Uuids.v7();
        String key = assetId.toString();
        store.write(key, content);

        Asset asset = new Asset(
                assetId,
                game.id(),
                actorUserId,
                format.mime(),
                content.length,
                size.width(),
                size.height(),
                sha256(content),
                sanitizeName(originalName),
                key,
                Instant.now());
        try {
            assets.insert(asset);
        } catch (RuntimeException e) {
            // Упавшая вставка не должна оставлять файл без строки: сборщик ищет мусор
            // по строкам `asset`, и такой файл не нашёл бы никто.
            store.delete(key);
            throw e;
        }
        return asset;
    }

    /**
     * Квота игры считается по сумме записанных байт, а не по счётчику: счётчик пришлось бы
     * чинить после каждой орфан-сборки, а сумма остаётся верной всегда.
     */
    private void requireQuotaLeft(UUID gameId, long addedBytes) {
        long used = assets.bytesByGame(gameId);
        if (used + addedBytes > settings.maxGameBytes()) {
            throw ApiException.badRequest(
                    "asset_quota_exceeded",
                    "Квота медиа на игру исчерпана: занято " + used
                            + " байт из " + settings.maxGameBytes());
        }
    }

    /**
     * Счётчик загрузок в полёте. Уменьшается в `finally`: иначе отказ (в том числе
     * исключением) навсегда занимал бы слот пользователя, и повторная попытка получала бы
     * отказ до перезапуска приложения.
     */
    private <T> T withUploadSlot(UUID userId, Supplier<T> action) {
        AtomicInteger counter = inFlight.computeIfAbsent(userId, key -> new AtomicInteger());
        if (counter.incrementAndGet() > settings.maxConcurrentUploads()) {
            counter.decrementAndGet();
            throw ApiException.tooManyRequests(
                    "asset_upload_busy", "Слишком много одновременных загрузок");
        }
        try {
            return action.get();
        } finally {
            counter.decrementAndGet();
        }
    }

    private Asset requireAsset(UUID assetId) {
        return assets.findById(assetId).orElseThrow(AssetService::assetNotFound);
    }

    /** Один и тот же отказ на «нет ассета», «чужая игра» и «нет участия» — см. javadoc `loadForRead`. */
    private static ApiException assetNotFound() {
        return ApiException.notFound("asset_not_found", "Ассет не найден");
    }

    /**
     * Имя файла приходит от клиента и сохраняется только ради показа. Путь и управляющие
     * символы вырезаются: имя не должно уметь притвориться путём в чужой системе.
     */
    static String sanitizeName(String originalName) {
        if (originalName == null || originalName.isBlank()) {
            return null;
        }
        String cleaned = originalName.replace('\\', '/');
        cleaned = cleaned.substring(cleaned.lastIndexOf('/') + 1);
        cleaned = cleaned.replaceAll("\\p{Cntrl}", "").strip();
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() <= 255 ? cleaned : cleaned.substring(0, 255);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
    }

    private Game requireMaster(UUID actorUserId, UUID gameId) {
        Game game = games.findById(gameId)
                .orElseThrow(() -> ApiException.notFound("game_not_found", "Игра не найдена"));
        Membership membership = memberships.find(gameId, actorUserId)
                .orElseThrow(() -> ApiException.forbidden("not_a_member", "Нет участия в этой игре"));
        if (membership.role() != MembershipRole.MASTER || membership.banned()) {
            throw ApiException.forbidden("not_master", "Загружать медиа может только мастер");
        }
        return game;
    }
}
