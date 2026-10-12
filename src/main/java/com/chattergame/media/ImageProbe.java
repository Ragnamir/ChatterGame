package com.chattergame.media;

import com.chattergame.util.ApiException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * Размеры картинки по заголовку файла.
 *
 * <p>Здесь принципиально <b>нет</b> вызова {@code reader.read()}: растр не выделяется
 * никогда. Лимит байт от бомбы декомпрессии не защищает — 10-мегабайтный PNG
 * распаковывается в 200+ мегапикселей и выжигает память воркера (`D-076`), поэтому
 * сначала читаются стороны, и файл, не прошедший лимит, до декодирования не доходит.
 *
 * <p>Метаданные при этом не нужны: вырезание EXIF идёт разбором контейнера
 * ({@link MetadataStripper}) и растра тоже не требует.
 */
final class ImageProbe {

    private ImageProbe() {
    }

    /**
     * @throws ApiException {@code asset_undecodable}, если заголовок не разобран: формат
     *                      угадан по магическим байтам, а внутри — не картинка этого формата
     */
    static Dimensions dimensions(byte[] bytes, ImageFormat format) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw ApiException.badRequest(
                        "asset_undecodable", "Файл не является изображением заявленного формата");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0) {
                    throw ApiException.badRequest(
                            "asset_undecodable", "Не удалось прочитать размеры изображения");
                }
                return new Dimensions(width, height);
            } finally {
                reader.dispose();
            }
        } catch (ApiException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw ApiException.badRequest(
                    "asset_undecodable", "Файл не читается как " + format.mime());
        }
    }

    record Dimensions(int width, int height) {
    }
}
