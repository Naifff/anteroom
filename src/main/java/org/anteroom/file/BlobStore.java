package org.anteroom.file;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;

import org.anteroom.config.DataDirectoryInitializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Шифротексты вложений на файловой системе, {@code data/blobs/<id>}.
 *
 * <p>Не в SQLite: BLOB'ы раздувают файл базы, а после массового удаления по TTL требуют
 * долгого {@code VACUUM}. На файловой системе место возвращается сразу, а бэкапом
 * становится каталог {@code data/} целиком.
 */
@Component
public class BlobStore {

    /** Недописанное тело лежит под этим суффиксом и переезжает на место только целиком. */
    public static final String PART_SUFFIX = ".part";

    private static final int BUFFER = 64 * 1024;

    private final Path dir;

    public BlobStore(@Value("${" + DataDirectoryInitializer.PROPERTY + ":"
            + DataDirectoryInitializer.DEFAULT + "}") String dataDir) {
        this.dir = Paths.get(dataDir).toAbsolutePath().normalize().resolve("blobs");
    }

    /**
     * Путь к блобу по его имени.
     *
     * <p>Имя приезжает из адреса скачивания, то есть снаружи. Проверяется не список
     * запрещённых символов, а результат: после разбора родителем обязан оказаться сам
     * каталог блобов. Так отсекаются и {@code ../}, и подкаталоги, и абсолютный путь.
     */
    public Path path(String id) {
        Path resolved = dir.resolve(id == null ? "" : id).normalize();
        if (!dir.equals(resolved.getParent())) {
            throw new IllegalArgumentException("недопустимое имя блоба");
        }
        return resolved;
    }

    /**
     * Пишет тело, обрывая чтение на пороге.
     *
     * <p>{@code limit} считается по фактически прочитанным байтам, а не по заголовку:
     * {@code Content-Length} присылает клиент, а при chunked-кодировании его нет вовсе.
     * Недописанный кусок в обоих случаях уходит с диска.
     *
     * @return сколько байт легло
     */
    public long write(String id, InputStream body, long limit) throws IOException {
        Path part = path(id + PART_SUFFIX);
        long total = 0;
        try (OutputStream out = Files.newOutputStream(part,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[BUFFER];
            int read;
            while ((read = body.read(buffer)) >= 0) {
                total += read;
                if (total > limit) {
                    throw new FileTooLargeException("тело больше объявленных " + limit + " байт");
                }
                out.write(buffer, 0, read);
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(part);
            throw e;
        }

        // Переезд на место — последним действием: блоб под своим именем всегда целый.
        Files.move(part, path(id), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        return total;
    }

    public boolean delete(String id) {
        try {
            return Files.deleteIfExists(path(id));
        } catch (IOException e) {
            throw new UncheckedIOException("не удалось удалить блоб", e);
        }
    }

    /** Что лежит в каталоге на самом деле — вместе с недописанными кусками. */
    public List<String> names() {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var paths = Files.list(dir)) {
            return paths.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("не удалось прочитать каталог блобов", e);
        }
    }
}
