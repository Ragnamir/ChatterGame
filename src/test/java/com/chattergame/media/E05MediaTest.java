package com.chattergame.media;

import com.chattergame.game.GameMutationService;
import com.chattergame.game.MembershipRole;
import com.chattergame.game.MembershipStatus;
import com.chattergame.user.E02TestSupport;
import com.chattergame.user.SessionCookie;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Медиа-ассеты (`PLAN` E05). Тесты идут через HTTP: предмет этапа — не хранение файла,
 * а отказ на входе и проверка права на выходе.
 *
 * <p>Картинки собираются в тесте настоящим `ImageIO`, а метаданные впечатываются после
 * записи. Так проверяется реальный путь: файл, который браузер действительно пришлёт,
 * с метаданными, которые действительно пишет камера, — и результат, из которого они
 * вырезаны. Проверяются байты, а не флаги: «поле обнулено» ничего не говорит о том,
 * что координаты исчезли из файла.
 *
 * <p>Каталог содержимого — временный и свой на класс: тест читает записанные файлы, и общий
 * каталог сделал бы результат зависящим от порядка запуска.
 */
class E05MediaTest extends E02TestSupport {

    private static final Path MEDIA_ROOT = createTempDir();

    /** 8×8 lossless WebP без метаданных; его `VP8L`-чанк служит основой расширенного файла. */
    private static final String WEBP_VP8L =
            "UklGRh4AAABXRUJQVlA4TBEAAAAvB8ABAAdQiirUo/+BiOh/AAA=";

    private static final String GPS = "GPSLatitude=55.7558N";

    @DynamicPropertySource
    static void mediaRoot(DynamicPropertyRegistry registry) {
        registry.add("chattergame.media.root", MEDIA_ROOT::toString);
        // Карантин свежих ассетов выключен: тест создаёт ассет и сразу зовёт сборку.
        registry.add("chattergame.media.orphan-grace-hours", () -> "0");
    }

    @Autowired
    private GameMutationService mutations;

    @Autowired
    private OrphanAssetCollector collector;

    // --- Отказы на входе ---------------------------------------------------------------

    /** Критерий приёмки `E05`: SVG и HTML отклоняются разными кодами (`D-074`). */
    @Test
    void svgAndHtmlAreRejectedWithDistinctCodes() throws Exception {
        Fixture fixture = masterWithGame();

        MvcResult svg = upload(fixture, "vector.svg",
                "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>");
        assertThat(svg.getResponse().getStatus()).isEqualTo(400);
        assertThat(field(svg, "code")).isEqualTo("asset_svg_rejected");

        MvcResult html = upload(fixture, "page.png",
                "<!doctype html><html><body>не картинка</body></html>");
        assertThat(html.getResponse().getStatus()).isEqualTo(400);
        assertThat(field(html, "code"))
                .as("HTML с расширением .png обязан быть отвергнут по содержимому")
                .isEqualTo("asset_html_rejected");
    }

    /** Критерий приёмки `E05`: архив с расширением `.png` отклоняется по несовпадению содержимого. */
    @Test
    void archiveWithImageExtensionIsRejectedByContent() throws Exception {
        Fixture fixture = masterWithGame();
        byte[] zip = {0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00, 0x08, 0x00, 0x00, 0x00};
        List<String> before = mediaFiles();

        MvcResult refused = upload(fixture, "evil.png", zip);

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(field(refused, "code")).isEqualTo("asset_unknown_format");
        assertThat(countRows("SELECT count(*) FROM asset WHERE game_id = ?", fixture.gameId()))
                .as("отказ не оставляет строку ассета")
                .isZero();
        assertThat(mediaFiles()).as("отказ не оставляет файл").isEqualTo(before);
    }

    /**
     * Критерий приёмки `E05`: PNG с 200 Мп отклоняется. Стороны берутся из заголовка,
     * поэтому тест объявляет их в `IHDR`, не выделяя растр: если бы проверка декодировала
     * картинку, тест съел бы память — то есть упал бы на самом дефекте.
     */
    @Test
    void decompressionBombIsRejectedBeforeDecoding() throws Exception {
        Fixture fixture = masterWithGame();
        byte[] bomb = pngWithDeclaredSize(png(8, 8), 20000, 20000);
        List<String> before = mediaFiles();

        MvcResult refused = upload(fixture, "huge.png", bomb);

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(field(refused, "code")).isEqualTo("asset_too_many_pixels");
        assertThat(field(refused, "detail")).contains("4096");
        assertThat(mediaFiles()).as("отклонённая бомба не попадает на диск").isEqualTo(before);
    }

    @Test
    void oversizeFileIsRejectedByByteLimit() throws Exception {
        Fixture fixture = masterWithGame();

        MvcResult refused = upload(fixture, "big.png", new byte[6 * 1024 * 1024]);

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(field(refused, "code")).isEqualTo("asset_too_large");
    }

    // --- Очистка метаданных ------------------------------------------------------------

    /** Критерий приёмки `E05`: EXIF с GPS удалён — проверка по байтам результата. */
    @Test
    void exifWithGpsIsRemovedFromStoredAndServedBytes() throws Exception {
        Fixture fixture = masterWithGame();
        byte[] withExif = jpegWithExif(pngFreeJpeg());
        assertThat(new String(withExif, StandardCharsets.ISO_8859_1))
                .as("исходник действительно несёт координаты, иначе тест ничего не проверяет")
                .contains(GPS);

        MvcResult created = upload(fixture, "photo.jpg", withExif);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        UUID assetId = UUID.fromString(field(created, "id"));
        assertThat(field(created, "mime")).isEqualTo("image/jpeg");

        byte[] stored = Files.readAllBytes(MEDIA_ROOT.resolve(assetId.toString()));
        assertThat(new String(stored, StandardCharsets.ISO_8859_1))
                .as("координаты не должны остаться в сохранённом файле (D-077)")
                .doesNotContain(GPS);
        assertThat(stored).as("картинка обязана остаться читаемой").startsWith(0xFF, 0xD8, 0xFF);

        MvcResult served = served(fixture, assetId);
        assertThat(served.getResponse().getStatus()).isEqualTo(200);
        assertThat(new String(served.getResponse().getContentAsByteArray(), StandardCharsets.ISO_8859_1))
                .doesNotContain(GPS);
    }

    /** Критерий приёмки `E05`: отдача идёт с `nosniff`, точным типом и долгим кэшем (`D-077`). */
    @Test
    void servedAssetCarriesExactTypeAndHardeningHeaders() throws Exception {
        Fixture fixture = masterWithGame();
        UUID assetId = uploadOk(fixture, "picture.png", png(8, 8));

        MvcResult served = served(fixture, assetId);

        assertThat(served.getResponse().getContentType()).isEqualTo("image/png");
        assertThat(served.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(served.getResponse().getHeader("Cache-Control"))
                .as("долгий кэш возможен из-за неперечислимого идентификатора (D-075)")
                .contains("max-age=31536000");
    }

    /**
     * `REQUIREMENTS §19` и `D-105`: браузерная съёмка даёт WebP, поэтому формат принимается,
     * а его `EXIF`-чанк вырезается как и всё остальное.
     */
    @Test
    void webpIsAcceptedAndItsExifChunkIsStripped() throws Exception {
        Fixture fixture = masterWithGame();

        MvcResult created = upload(fixture, "shot.webp", webpWithExif());

        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        assertThat(field(created, "mime")).isEqualTo("image/webp");
        UUID assetId = UUID.fromString(field(created, "id"));

        byte[] served = served(fixture, assetId).getResponse().getContentAsByteArray();
        assertThat(new String(served, StandardCharsets.ISO_8859_1))
                .as("EXIF-чанк обязан исчезнуть из контейнера")
                .doesNotContain("EXIF")
                .doesNotContain(GPS);
        assertThat(served).as("контейнер WebP остаётся читаемым").startsWith(0x52, 0x49, 0x46, 0x46);
    }

    /** `D-105`: WebP в whitelist, а декодирует его плагин — без него формат «принят» только на словах. */
    @Test
    void webpDecoderIsAvailable() {
        assertThat(ImageIO.getImageReadersByFormatName("webp").hasNext())
                .as("webp в whitelist REQUIREMENTS §19 требует настоящего читателя (D-105)")
                .isTrue();
    }

    /** Комментарий GIF вырезается, а рисунок остаётся читаемым: вырезается блок, не растр. */
    @Test
    void gifCommentIsStrippedAndImageStaysReadable() throws Exception {
        Fixture fixture = masterWithGame();
        UUID assetId = uploadOk(fixture, "dot.gif", gifWithComment());

        byte[] served = served(fixture, assetId).getResponse().getContentAsByteArray();

        assertThat(new String(served, StandardCharsets.ISO_8859_1)).doesNotContain(GPS);
        assertThat(served).startsWith(0x47, 0x49, 0x46, 0x38);
        assertThat(ImageIO.read(new ByteArrayInputStream(served)))
                .as("GIF после вырезания комментария обязан читаться")
                .isNotNull();
    }

    // --- Права -------------------------------------------------------------------------

    /** Критерий приёмки `E05`: посторонний получает `404`, а не `403` (`D-075`). */
    @Test
    void strangerGetsNotFoundInsteadOfForbidden() throws Exception {
        Fixture fixture = masterWithGame();
        UUID assetId = uploadOk(fixture, "secret.png", png(8, 8));

        String stranger = registerAndGetToken(unique("Посторонний"), PASSWORD);
        MvcResult refused = mvc.perform(get("/games/" + fixture.gameId() + "/assets/" + assetId)
                .cookie(new Cookie(SessionCookie.NAME, stranger))).andReturn();

        assertThat(refused.getResponse().getStatus())
                .as("ответ не должен отличаться от «ассета нет»: иначе он выдаёт чужую игру")
                .isEqualTo(404);
        assertThat(field(refused, "code")).isEqualTo("asset_not_found");
    }

    @Test
    void playerCannotUploadMedia() throws Exception {
        Fixture fixture = masterWithGame();
        String login = unique("Игрок");
        register(login, PASSWORD, null);
        UUID playerId = jdbc.queryForObject(
                "SELECT id FROM app_user WHERE login_normalized = ?", UUID.class, loginNormalized(login));
        mutations.addMembership(
                fixture.userId(),
                fixture.gameId(),
                playerId,
                MembershipRole.PLAYER,
                MembershipStatus.JOINED);

        MvcResult refused = upload(fixture.gameId(), loginAndGetToken(login, PASSWORD), "player.png", png(8, 8));

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
    }

    // --- Картинка локации и замена -----------------------------------------------------

    /** Привязка картинки — мутация мира, значит она обязана оставить событие (`D-033`). */
    @Test
    void attachingLocationImageLeavesOneEventPerChange() throws Exception {
        Fixture fixture = masterWithGame();
        UUID location = fixture.location("Таверна");
        UUID assetId = uploadOk(fixture, "tavern.png", png(8, 8));

        MvcResult patched = fixture.setImage(location, assetId);
        assertThat(patched.getResponse().getStatus()).isEqualTo(200);
        assertThat(field(patched, "imageAssetId")).isEqualTo(assetId.toString());

        assertThat(eventTypes(fixture.gameId()))
                .as("установка картинки пишет событие правки содержимого локации")
                .containsExactly("GAME_CREATED", "CHAT_CREATED", "CHAT_CONTENT_CHANGED");
    }

    /**
     * Критерий приёмки `E05`: замена атомарна и освобождает прежний файл, а отклонённая
     * загрузка не обнуляет уже стоящую картинку (`D-077`).
     */
    @Test
    void replacingLocationImageReleasesOldAssetAndSurvivesRejectedUpload() throws Exception {
        Fixture fixture = masterWithGame();
        UUID location = fixture.location("Склад");
        UUID first = uploadOk(fixture, "first.png", png(8, 8));
        fixture.setImage(location, first);

        MvcResult rejected = upload(fixture, "second.png", pngWithDeclaredSize(png(8, 8), 20000, 20000));
        assertThat(rejected.getResponse().getStatus()).isEqualTo(400);
        assertThat(imageOf(location))
                .as("отклонённая загрузка не трогает ссылку")
                .isEqualTo(first);
        assertThat(served(fixture, first).getResponse().getStatus())
                .as("прежняя картинка остаётся доступной")
                .isEqualTo(200);

        UUID second = uploadOk(fixture, "second.png", png(16, 16));
        fixture.setImage(location, second);

        assertThat(imageOf(location)).isEqualTo(second);
        assertThat(countRows("SELECT count(*) FROM asset WHERE id = ?", first))
                .as("замещённый ассет снимается вместе с файлом (D-077)")
                .isZero();
        assertThat(MEDIA_ROOT.resolve(first.toString())).doesNotExist();
        assertThat(MEDIA_ROOT.resolve(second.toString())).exists();

        fixture.setImage(location, null);
        assertThat(imageOf(location))
                .as("снятие картинки — это пустая ссылка, а не удалённая локация")
                .isNull();
        assertThat(MEDIA_ROOT.resolve(second.toString())).doesNotExist();
    }

    @Test
    void assetFromAnotherGameCannotBeAttached() throws Exception {
        Fixture mine = masterWithGame();
        Fixture other = masterWithGame();
        UUID foreign = uploadOk(other, "foreign.png", png(8, 8));

        MvcResult refused = mine.setImage(mine.location("Зал"), foreign);

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(field(refused, "code")).isEqualTo("asset_other_game");
    }

    // --- Орфан-сборка ------------------------------------------------------------------

    /**
     * Критерий приёмки `E05`: сборка удаляет только незадействованные ассеты; сухой прогон
     * называет кандидатов и ничего не трогает.
     */
    @Test
    void orphanCollectionTouchesOnlyUnreferencedAssets() throws Exception {
        Fixture fixture = masterWithGame();
        UUID location = fixture.location("Двор");
        UUID referenced = uploadOk(fixture, "used.png", png(8, 8));
        fixture.setImage(location, referenced);
        UUID orphan = uploadOk(fixture, "lost.png", png(8, 8));

        OrphanAssetCollector.OrphanReport dry = collector.collect(true);
        assertThat(dry.dryRun()).isTrue();
        assertThat(dry.candidates()).contains(orphan).doesNotContain(referenced);
        assertThat(dry.deleted()).isEmpty();
        assertThat(MEDIA_ROOT.resolve(orphan.toString()))
                .as("сухой прогон не удаляет файл")
                .exists();

        OrphanAssetCollector.OrphanReport real = collector.collect(false);
        assertThat(real.deleted())
                .as("сборка берёт бесхозный ассет и не трогает привязанный")
                .contains(orphan)
                .doesNotContain(referenced);
        assertThat(countRows("SELECT count(*) FROM asset WHERE id = ?", orphan)).isZero();
        assertThat(MEDIA_ROOT.resolve(orphan.toString())).doesNotExist();
        assertThat(countRows("SELECT count(*) FROM asset WHERE id = ?", referenced))
                .as("ассет живой локации не трогается")
                .isEqualTo(1);
    }

    /**
     * Критерий приёмки `E05`: ассет архивированного персонажа живой игры сборка не трогает —
     * на него ссылается транскрипт (`D-077`).
     */
    @Test
    void archivedCharacterAssetSurvivesOrphanCollection() throws Exception {
        Fixture fixture = masterWithGame();
        UUID character = fixture.character("Старик");
        UUID portrait = uploadOk(fixture, "portrait.png", png(8, 8));
        jdbc.update(
                "UPDATE character SET portrait_asset_id = ?, archived = true WHERE id = ?",
                portrait,
                character);
        UUID orphan = uploadOk(fixture, "lost.png", png(8, 8));

        OrphanAssetCollector.OrphanReport report = collector.collect(false);

        assertThat(report.deleted()).contains(orphan).doesNotContain(portrait);
        assertThat(countRows("SELECT count(*) FROM asset WHERE id = ?", portrait))
                .as("ссылка из архивного персонажа считается упоминанием")
                .isEqualTo(1);
        assertThat(MEDIA_ROOT.resolve(portrait.toString())).exists();
    }

    // --- Обвязка -----------------------------------------------------------------------

    private static Path createTempDir() {
        try {
            return Files.createTempDirectory("chattergame-e05-media");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Снимок каталога: тесты делят его между собой, поэтому «пусто» проверяется по разнице. */
    private List<String> mediaFiles() throws IOException {
        if (!Files.isDirectory(MEDIA_ROOT)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(MEDIA_ROOT)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    private UUID imageOf(UUID chatId) {
        return jdbc.queryForObject("SELECT image_asset_id FROM chat WHERE id = ?", UUID.class, chatId);
    }

    private MvcResult upload(Fixture fixture, String name, byte[] content) throws Exception {
        return upload(fixture.gameId(), fixture.token(), name, content);
    }

    private MvcResult upload(Fixture fixture, String name, String content) throws Exception {
        return upload(fixture, name, content.getBytes(StandardCharsets.UTF_8));
    }

    private MvcResult upload(UUID gameId, String token, String name, byte[] content) throws Exception {
        return mvc.perform(multipart("/games/" + gameId + "/assets")
                        .file(new MockMultipartFile("file", name, "application/octet-stream", content))
                        .cookie(new Cookie(SessionCookie.NAME, token)))
                .andReturn();
    }

    private UUID uploadOk(Fixture fixture, String name, byte[] content) throws Exception {
        MvcResult created = upload(fixture, name, content);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        return UUID.fromString(field(created, "id"));
    }

    private MvcResult served(Fixture fixture, UUID assetId) throws Exception {
        return mvc.perform(get("/games/" + fixture.gameId() + "/assets/" + assetId)
                .cookie(new Cookie(SessionCookie.NAME, fixture.token()))).andReturn();
    }

    private List<String> eventTypes(UUID gameId) {
        return jdbc.queryForList(
                "SELECT type FROM game_event WHERE game_id = ? ORDER BY seq", String.class, gameId);
    }

    private String field(MvcResult result, String name) throws Exception {
        JsonNode node = json.readTree(bodyOf(result)).get(name);
        return node == null || node.isNull() ? null : node.asText();
    }

    // --- Сборка картинок ---------------------------------------------------------------

    private static byte[] png(int width, int height) throws IOException {
        return encode(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png");
    }

    private static byte[] pngFreeJpeg() throws IOException {
        return encode(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "jpeg");
    }

    private static byte[] encode(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    /**
     * JPEG с настоящим сегментом APP1: файл, записанный `ImageIO`, является полноценным
     * JPEG, а метаданные впечатываются сразу после `SOI` — как это делает камера.
     */
    private static byte[] jpegWithExif(byte[] jpeg) throws IOException {
        byte[] payload = concat(
                "Exif\u0000\u0000".getBytes(StandardCharsets.ISO_8859_1),
                GPS.getBytes(StandardCharsets.ISO_8859_1));
        int length = payload.length + 2;
        ByteArrayOutputStream segment = new ByteArrayOutputStream();
        segment.write(0xFF);
        segment.write(0xE1);
        segment.write(length >> 8);
        segment.write(length & 0xFF);
        segment.write(payload);
        return insertAt(jpeg, 2, segment.toByteArray());
    }

    /** GIF с расширением комментария: `0x21 0xFE`, подблоки, терминатор `0x00`. */
    private static byte[] gifWithComment() throws IOException {
        byte[] gif = encode(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "gif");
        byte[] text = GPS.getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream extension = new ByteArrayOutputStream();
        extension.write(0x21);
        extension.write(0xFE);
        extension.write(text.length);
        extension.write(text);
        extension.write(0x00);
        return insertAt(gif, 13 + globalColorTableSize(gif), extension.toByteArray());
    }

    private static int globalColorTableSize(byte[] gif) {
        return (gif[10] & 0x80) == 0 ? 0 : 3 * (1 << ((gif[10] & 0x07) + 1));
    }

    /**
     * Расширенный WebP: `VP8X` объявляет метаданные, `EXIF` их несёт. Собран вручную,
     * потому что записать WebP из JDK нечем — плагин умеет только читать (`D-105`).
     */
    private static byte[] webpWithExif() throws IOException {
        byte[] base = Base64.getDecoder().decode(WEBP_VP8L);
        byte[] vp8l = Arrays.copyOfRange(base, 12, base.length);

        ByteArrayOutputStream vp8x = new ByteArrayOutputStream();
        vp8x.write("VP8X".getBytes(StandardCharsets.US_ASCII));
        vp8x.write(new byte[]{10, 0, 0, 0});
        vp8x.write(0x08);
        vp8x.write(new byte[]{0, 0, 0});
        vp8x.write(new byte[]{7, 0, 0});
        vp8x.write(new byte[]{7, 0, 0});

        byte[] payload = concat(
                "Exif\u0000\u0000".getBytes(StandardCharsets.ISO_8859_1),
                GPS.getBytes(StandardCharsets.ISO_8859_1));
        ByteArrayOutputStream exif = new ByteArrayOutputStream();
        exif.write("EXIF".getBytes(StandardCharsets.US_ASCII));
        exif.write(payload.length);
        exif.write(payload.length >> 8);
        exif.write(0);
        exif.write(0);
        exif.write(payload);

        ByteArrayOutputStream chunks = new ByteArrayOutputStream();
        chunks.write(vp8x.toByteArray());
        chunks.write(vp8l);
        chunks.write(exif.toByteArray());
        byte[] body = chunks.toByteArray();

        ByteArrayOutputStream riff = new ByteArrayOutputStream();
        riff.write("RIFF".getBytes(StandardCharsets.US_ASCII));
        int size = 4 + body.length;
        riff.write(new byte[]{(byte) size, (byte) (size >> 8), (byte) (size >> 16), (byte) (size >> 24)});
        riff.write("WEBP".getBytes(StandardCharsets.US_ASCII));
        riff.write(body);
        return riff.toByteArray();
    }

    /** Объявленные стороны в `IHDR` больше настоящих: проверка обязана читать заголовок. */
    private static byte[] pngWithDeclaredSize(byte[] png, int width, int height) {
        byte[] patched = png.clone();
        writeInt(patched, 16, width);
        writeInt(patched, 20, height);
        CRC32 crc = new CRC32();
        crc.update(patched, 12, 17);
        writeInt(patched, 29, (int) crc.getValue());
        return patched;
    }

    private static void writeInt(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >> 24);
        target[offset + 1] = (byte) (value >> 16);
        target[offset + 2] = (byte) (value >> 8);
        target[offset + 3] = (byte) value;
    }

    private static byte[] insertAt(byte[] source, int offset, byte[] insert) {
        byte[] result = new byte[source.length + insert.length];
        System.arraycopy(source, 0, result, 0, offset);
        System.arraycopy(insert, 0, result, offset, insert.length);
        System.arraycopy(source, offset, result, offset + insert.length, source.length - offset);
        return result;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private Fixture masterWithGame() throws Exception {
        String login = unique("Мастер");
        String token = registerAndGetToken(login, PASSWORD);
        MvcResult created = call(post("/games"), token, Map.of("title", "Игра " + unique("М")));
        UUID gameId = UUID.fromString(field(created, "id"));
        UUID userId = jdbc.queryForObject(
                "SELECT id FROM app_user WHERE login_normalized = ?", UUID.class, loginNormalized(login));
        return new Fixture(gameId, userId, token);
    }

    private final class Fixture {

        private final UUID gameId;
        private final UUID userId;
        private final String token;

        private Fixture(UUID gameId, UUID userId, String token) {
            this.gameId = gameId;
            this.userId = userId;
            this.token = token;
        }

        private UUID gameId() {
            return gameId;
        }

        private UUID userId() {
            return userId;
        }

        private String token() {
            return token;
        }

        private UUID location(String title) throws Exception {
            MvcResult created = call(post("/games/" + gameId + "/locations"), token,
                    Map.of("title", title, "description", "описание " + title));
            assertThat(created.getResponse().getStatus()).isEqualTo(201);
            return UUID.fromString(field(created, "id"));
        }

        private UUID character(String name) throws Exception {
            MvcResult created = call(post("/games/" + gameId + "/characters"), token,
                    Map.of("name", name));
            assertThat(created.getResponse().getStatus()).isEqualTo(201);
            return UUID.fromString(field(created, "id"));
        }

        private MvcResult setImage(UUID chatId, UUID assetId) throws Exception {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("assetId", assetId == null ? null : assetId.toString());
            return call(put("/games/" + gameId + "/locations/" + chatId + "/image"), token, payload);
        }
    }
}
