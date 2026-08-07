package org.anteroom.file;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Вложения по HTTP, а не по сокету: двадцать мегабайт по каналу сообщений забили бы
 * ленту всем участникам комнаты.
 *
 * <p>Тело — сырой {@code application/octet-stream} без multipart: разбирать нечего,
 * это уже шифротекст.
 */
@RestController
public class FileController {

    /**
     * Пропуск едет заголовком, а не параметром запроса: параметры попадают в access.log
     * веб-сервера, и одноразовость токена этого не отменяет.
     */
    public static final String TOKEN_HEADER = "X-Upload-Token";

    private final FileService files;
    private final BlobStore blobs;

    public FileController(FileService files, BlobStore blobs) {
        this.files = files;
        this.blobs = blobs;
    }

    @PostMapping(value = "/api/file", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Void> upload(
            @RequestHeader(name = TOKEN_HEADER, required = false) String token,
            HttpServletRequest request) throws IOException {

        // Заголовок отдаётся как есть: он приходит от клиента, и решение по нему
        // принимает FileService — вместе со второй проверкой, по прочитанным байтам.
        files.accept(token, request.getInputStream(), request.getContentLengthLong());
        return ResponseEntity.noContent().build();
    }

    /**
     * Скачивание открыто без входа: адрес блоба и есть пропуск к нему. Внутри
     * шифротекст под случайным ключом файла, а ключ живёт в сообщении комнаты —
     * угадавший имя получает мусор.
     *
     * <p>Так же это нужно фазе 9: одноразовая ссылка отдаёт файл, не выдавая ключ комнаты.
     */
    @GetMapping("/api/file/{id}")
    public ResponseEntity<Resource> download(@PathVariable String id) {
        StoredFile stored = files.find(id);
        if (stored == null) {
            // Протухший и несуществующий отвечают одинаково: разница наружу — это способ
            // узнать, что здесь что-то было.
            return ResponseEntity.notFound().build();
        }

        Path path;
        try {
            path = blobs.path(id);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
        if (!Files.isReadable(path)) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(stored.sizeBytes())
                // Шифротексту в кэше браузера делать нечего: файл живёт по TTL, а кэш
                // об этом не знает и переживёт удаление.
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new FileSystemResource(path));
    }

    @ExceptionHandler(UploadRefusedException.class)
    public ResponseEntity<Void> refused() {
        return ResponseEntity.status(403).build();
    }

    @ExceptionHandler(FileTooLargeException.class)
    public ResponseEntity<Void> tooLarge() {
        return ResponseEntity.status(413).build();
    }
}
