package com.chattergame.media;

import com.chattergame.util.ApiException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;

/**
 * Файловое хранилище ассетов (`REQUIREMENTS §19`).
 *
 * <p>Запись идёт во временный файл в том же каталоге и переименованием на место: переезд
 * в пределах одной файловой системы атомарен, поэтому по ключу никогда не видно половину
 * файла. Прежнее содержимое под этим ключом при этом не портится до самого переименования,
 * а ключи у ассетов разные — значит «обнулить прошлый портрет» нечем.
 */
@Component
public class FilesystemAssetStore implements AssetStore {

    /** Ключ приходит от приложения, но путь собирается по нему: выход за корень — отказ. */
    private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z0-9._-]+");

    private final Path root;

    public FilesystemAssetStore(MediaSettings settings) {
        this.root = settings.root().toAbsolutePath().normalize();
    }

    @Override
    public void write(String key, byte[] content) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), ".upload-", ".part");
            try {
                Files.write(temp, content);
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Files.deleteIfExists(temp);
                throw e;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось сохранить ассет", e);
        }
    }

    @Override
    public byte[] read(String key) {
        try {
            return Files.readAllBytes(resolve(key));
        } catch (IOException e) {
            throw ApiException.notFound("asset_content_missing", "Содержимое ассета не найдено");
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.isRegularFile(resolve(key));
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось удалить ассет", e);
        }
    }

    private Path resolve(String key) {
        if (key == null || !SAFE_KEY.matcher(key).matches()) {
            throw ApiException.badRequest("asset_bad_key", "Недопустимый ключ ассета");
        }
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root)) {
            throw ApiException.badRequest("asset_bad_key", "Недопустимый ключ ассета");
        }
        return resolved;
    }
}
