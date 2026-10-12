package com.chattergame.media;

import com.chattergame.util.ApiException;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Разрешённые форматы медиа (`REQUIREMENTS §19`).
 *
 * <p>Формат определяется по содержимому, а не по расширению и не по `Content-Type` от
 * клиента: и то и другое подставляет загружающий, а решение «этот файл безопасно отдать
 * обратно» принимается по байтам. Проверка расширения здесь была бы проверкой имени,
 * а не проверкой файла.
 *
 * <p>SVG и HTML отклоняются отдельными кодами, а не общим «неизвестный формат»: SVG — это
 * скрипт, который выполнится в сессии пользователя на нашем домене и получит доступ
 * к аккаунту (`D-074`), и в логе должно быть видно, что попытка была именно такая.
 */
public enum ImageFormat {

    JPEG("image/jpeg"),
    PNG("image/png"),
    WEBP("image/webp"),
    GIF("image/gif");

    /** Сколько байт просматривается на разметку: тег в начале файла, а не в конце. */
    private static final int MARKUP_WINDOW = 4096;

    private final String mime;

    ImageFormat(String mime) {
        this.mime = mime;
    }

    /** Точный `Content-Type` отдачи: ставится из сохранённого значения, не угадывается. */
    public String mime() {
        return mime;
    }

    public static ImageFormat detect(byte[] bytes) {
        if (bytes == null || bytes.length < 12) {
            throw ApiException.badRequest("asset_unknown_format", unsupportedMessage());
        }
        String head = ascii(bytes, Math.min(bytes.length, MARKUP_WINDOW));

        if (looksLikeHtmlDocument(head)) {
            throw ApiException.badRequest(
                    "asset_html_rejected", "HTML не принимается: допустимы jpeg, png, webp, gif");
        }
        // SVG проверяется до одинокого `<script`: сам `D-074` описывает SVG как файл
        // со скриптом внутри, поэтому «svg + script» обязан называться SVG, а не HTML.
        if (looksLikeSvg(head)) {
            throw ApiException.badRequest(
                    "asset_svg_rejected", "SVG не принимается: внутри страницы выполнится чужой скрипт");
        }
        if (looksLikeHtmlFragment(head)) {
            throw ApiException.badRequest(
                    "asset_html_rejected", "HTML не принимается: допустимы jpeg, png, webp, gif");
        }
        if (startsWith(bytes, 0xFF, 0xD8, 0xFF)) {
            return JPEG;
        }
        if (startsWith(bytes, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return PNG;
        }
        if (startsWith(bytes, 0x47, 0x49, 0x46, 0x38)
                && (bytes[4] == '7' || bytes[4] == '9') && bytes[5] == 'a') {
            return GIF;
        }
        if (startsWith(bytes, 0x52, 0x49, 0x46, 0x46) && matchesAt(bytes, 8, "WEBP")) {
            return WEBP;
        }
        throw ApiException.badRequest("asset_unknown_format", unsupportedMessage());
    }

    private static String unsupportedMessage() {
        return "Формат не поддерживается: допустимы jpeg, png, webp, gif";
    }

    /**
     * SVG узнаётся по тегу, а не по `<?xml`: пролог необязателен, а `<?xml` бывает и
     * у XHTML, который надо отклонить как HTML.
     */
    private static boolean looksLikeSvg(String head) {
        return head.contains("<svg") || (head.contains("<?xml") && head.contains("svg"));
    }

    /** Признак HTML-документа: пролог или корневой тег. Проверяется раньше SVG намеренно
     *  — страница с встроенным `<svg>` остаётся страницей. */
    private static boolean looksLikeHtmlDocument(String head) {
        return head.contains("<!doctype") || head.contains("<html");
    }

    /**
     * Признак HTML-фрагмента: `<script>` или `<iframe>` без обрамляющего документа.
     * Отдельно от {@link #looksLikeHtmlDocument}, чтобы не перехватывать вставленный
     * скрипт у SVG: там скрипт — часть вектора атаки, а не признак страницы.
     */
    private static boolean looksLikeHtmlFragment(String head) {
        return head.contains("<script") || head.contains("<iframe");
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesAt(byte[] bytes, int offset, String ascii) {
        for (int i = 0; i < ascii.length(); i++) {
            if (offset + i >= bytes.length || bytes[offset + i] != ascii.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** Latin-1: байты разметки читаются как ASCII, а бинарная часть не рушит декодирование. */
    private static String ascii(byte[] bytes, int length) {
        return new String(bytes, 0, Math.min(length, bytes.length), StandardCharsets.ISO_8859_1)
                .toLowerCase(Locale.ROOT);
    }
}
