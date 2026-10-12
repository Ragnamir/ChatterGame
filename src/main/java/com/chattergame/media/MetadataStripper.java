package com.chattergame.media;

import com.chattergame.util.ApiException;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Вырезание метаданных разбором контейнера (`D-077`).
 *
 * <p>Файл не перекодируется: удаляются только блоки метаданных, а сжатые данные остаются
 * байт в байт. Это не оптимизация, а условие корректности — перекодирование через
 * {@code ImageIO} сломало бы анимированный GIF (читается только первый кадр) и потеряло бы
 * качество JPEG, а записать WebP нечем вовсе: у плагина есть только читатель.
 *
 * <p>Что вырезается: EXIF и XMP с GPS и автором, комментарии. Что остаётся: цветовые
 * профили, прозрачность, анимация, а также размеры и пиксели. Контейнер после правки
 * обязан остаться читаемым — это проверяется в {@link #strip}, а не предполагается.
 */
final class MetadataStripper {

    private static final Set<String> PNG_DROPPED_CHUNKS = Set.of("eXIf", "tEXt", "zTXt", "iTXt");
    private static final Set<String> WEBP_DROPPED_CHUNKS = Set.of("EXIF", "XMP ");

    /** Биты флагов `VP8X`: если чанк вырезан, флаг обязан погаснуть, иначе файл противоречив. */
    private static final int WEBP_FLAG_EXIF = 0x08;
    private static final int WEBP_FLAG_XMP = 0x04;

    private MetadataStripper() {
    }

    static byte[] strip(byte[] bytes, ImageFormat format) {
        byte[] stripped = switch (format) {
            case JPEG -> stripJpeg(bytes);
            case PNG -> stripPng(bytes);
            case GIF -> stripGif(bytes);
            case WEBP -> stripWebp(bytes);
        };
        // Контейнер собирается вручную, поэтому результат проверяется тем же чтением
        // заголовка: битый после правки файл должен падать здесь, а не у игрока в браузере.
        ImageProbe.dimensions(stripped, format);
        return stripped;
    }

    /**
     * JPEG: сегменты до начала сжатых данных. Удаляются APP1 (EXIF и XMP), APP13
     * (IPTC и Photoshop) и COM (комментарий), остальное копируется как есть. Сжатые
     * данные после `SOS` не разбираются — маркеров в них нет.
     */
    private static byte[] stripJpeg(byte[] bytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length);
        out.write(bytes, 0, 2);
        int offset = 2;
        while (offset + 1 < bytes.length) {
            if ((bytes[offset] & 0xFF) != 0xFF) {
                throw ApiException.badRequest("asset_undecodable", "Повреждённая структура JPEG");
            }
            int marker = bytes[offset + 1] & 0xFF;
            if (marker == 0xDA || marker == 0xD9) {
                out.write(bytes, offset, bytes.length - offset);
                return out.toByteArray();
            }
            if (marker == 0xFF || marker == 0x00) {
                offset++;
                continue;
            }
            if (offset + 3 >= bytes.length) {
                throw ApiException.badRequest("asset_undecodable", "Обрезанный сегмент JPEG");
            }
            int length = ((bytes[offset + 2] & 0xFF) << 8) | (bytes[offset + 3] & 0xFF);
            if (length < 2 || offset + 2 + length > bytes.length) {
                throw ApiException.badRequest("asset_undecodable", "Обрезанный сегмент JPEG");
            }
            boolean metadata = marker == 0xE1 || marker == 0xED || marker == 0xFE;
            if (!metadata) {
                out.write(bytes, offset, 2 + length);
            }
            offset += 2 + length;
        }
        throw ApiException.badRequest("asset_undecodable", "В JPEG не найден конец заголовка");
    }

    /** PNG: чанки метаданных вырезаются целиком, включая поле контрольной суммы. */
    private static byte[] stripPng(byte[] bytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length);
        out.write(bytes, 0, 8);
        int offset = 8;
        while (offset + 8 <= bytes.length) {
            int length = readInt(bytes, offset);
            if (length < 0 || offset + 12 + length > bytes.length) {
                throw ApiException.badRequest("asset_undecodable", "Обрезанный чанк PNG");
            }
            String type = new String(bytes, offset + 4, 4, StandardCharsets.US_ASCII);
            if (!PNG_DROPPED_CHUNKS.contains(type)) {
                out.write(bytes, offset, 12 + length);
            }
            offset += 12 + length;
            if ("IEND".equals(type)) {
                return out.toByteArray();
            }
        }
        throw ApiException.badRequest("asset_undecodable", "В PNG не найден IEND");
    }

    /**
     * GIF: вырезается расширение комментария. Расширения приложения (в них счётчик циклов
     * анимации и данные XMP) не трогаются: удалив их, мы сломали бы анимацию, а GPS в GIF
     * не хранится — этот формат не несёт EXIF.
     */
    private static byte[] stripGif(byte[] bytes) {
        if (bytes.length < 13) {
            throw ApiException.badRequest("asset_undecodable", "Обрезанный GIF");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length);
        int offset = 13 + globalColorTableSize(bytes);
        if (offset > bytes.length) {
            throw ApiException.badRequest("asset_undecodable", "Обрезанная палитра GIF");
        }
        out.write(bytes, 0, offset);

        while (offset < bytes.length) {
            int block = bytes[offset] & 0xFF;
            if (block == 0x3B) {
                out.write(bytes, offset, bytes.length - offset);
                return out.toByteArray();
            }
            if (block == 0x21) {
                if (offset + 1 >= bytes.length) {
                    throw ApiException.badRequest("asset_undecodable", "Обрезанное расширение GIF");
                }
                int label = bytes[offset + 1] & 0xFF;
                int end = skipSubBlocks(bytes, offset + 2);
                if (label != 0xFE) {
                    out.write(bytes, offset, end - offset);
                }
                offset = end;
                continue;
            }
            if (block == 0x2C) {
                int tableSize = localColorTableSize(bytes, offset);
                // Дескриптор 10 байт, затем палитра, затем байт минимального размера кода LZW.
                int dataStart = skipSubBlocks(bytes, offset + 11 + tableSize);
                out.write(bytes, offset, dataStart - offset);
                offset = dataStart;
                continue;
            }
            throw ApiException.badRequest("asset_undecodable", "Неизвестный блок GIF");
        }
        throw ApiException.badRequest("asset_undecodable", "В GIF не найден трейлер");
    }

    /**
     * WebP: чанки `EXIF` и `XMP ` удаляются, а одноимённые флаги в `VP8X` гасятся —
     * иначе контейнер объявляет метаданные, которых в нём нет.
     */
    private static byte[] stripWebp(byte[] bytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length);
        out.write(bytes, 0, 12);
        int offset = 12;
        while (offset + 8 <= bytes.length) {
            String fourcc = new String(bytes, offset, 4, StandardCharsets.US_ASCII);
            int size = readIntLe(bytes, offset + 4);
            if (size < 0 || offset + 8 + size > bytes.length) {
                throw ApiException.badRequest("asset_undecodable", "Обрезанный чанк WebP");
            }
            if (!WEBP_DROPPED_CHUNKS.contains(fourcc)) {
                // Байт выравнивания нечётного чанка копируется вместе с данными: без него
                // следующий чанк читается со сдвигом на байт, и файл разваливается.
                int padded = size + (size % 2);
                byte[] chunk = new byte[8 + padded];
                System.arraycopy(bytes, offset, chunk, 0, chunk.length);
                if ("VP8X".equals(fourcc) && size >= 1) {
                    chunk[8] = (byte) (chunk[8] & ~(WEBP_FLAG_EXIF | WEBP_FLAG_XMP));
                }
                out.write(chunk, 0, chunk.length);
            }
            offset += 8 + size + (size % 2);
        }
        byte[] result = out.toByteArray();
        writeIntLe(result, 4, result.length - 8);
        return result;
    }

    private static int globalColorTableSize(byte[] bytes) {
        return (bytes[10] & 0x80) == 0 ? 0 : 3 * (1 << ((bytes[10] & 0x07) + 1));
    }

    private static int localColorTableSize(byte[] bytes, int descriptor) {
        if (descriptor + 10 > bytes.length) {
            throw ApiException.badRequest("asset_undecodable", "Обрезанный дескриптор GIF");
        }
        return (bytes[descriptor + 9] & 0x80) == 0 ? 0 : 3 * (1 << ((bytes[descriptor + 9] & 0x07) + 1));
    }

    /** Конец цепочки подблоков GIF: длина, данные, повтор; ноль — признак конца. */
    private static int skipSubBlocks(byte[] bytes, int offset) {
        while (offset < bytes.length) {
            int size = bytes[offset] & 0xFF;
            if (size == 0) {
                return offset + 1;
            }
            offset += 1 + size;
        }
        throw ApiException.badRequest("asset_undecodable", "Обрезанный подблок GIF");
    }

    private static int readInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24) | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8) | (bytes[offset + 3] & 0xFF);
    }

    private static int readIntLe(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16) | ((bytes[offset + 3] & 0xFF) << 24);
    }

    private static void writeIntLe(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >> 8);
        bytes[offset + 2] = (byte) (value >> 16);
        bytes[offset + 3] = (byte) (value >> 24);
    }
}
