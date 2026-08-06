package org.anteroom.file;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import org.anteroom.room.Room;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * HTTP-слой вложений. Токен здесь выдаётся напрямую — как он выдаётся по сокету,
 * проверяет {@code RoomProtocolTest}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = { "app.data-dir=build/test-data-file-http", "app.challenge.per-ip-limit=10000" })
class FileEndpointTest {

    private static final Path DATA_DIR = Path.of("build/test-data-file-http");
    private static final String ROOM = "room-http";
    private static final String DEVICE = "device-1";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FileService files;

    @BeforeAll
    static void startFromEmptyDirectory() throws IOException {
        if (!Files.exists(DATA_DIR)) {
            return;
        }
        try (var paths = Files.walk(DATA_DIR)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        jdbc.update("DELETE FROM file");
        jdbc.update("DELETE FROM room WHERE id = ?", ROOM);
        jdbc.update("INSERT INTO room (id, created_at) VALUES (?, ?)", ROOM, 0);

        Path blobDir = DATA_DIR.resolve("blobs");
        Files.createDirectories(blobDir);
        try (var paths = Files.list(blobDir)) {
            paths.forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    @Test
    void roundTripsCiphertextByteForByte() {
        byte[] body = new byte[100_000];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) (i * 31);
        }
        Upload upload = files.issue(room(), DEVICE, body.length, 3600L);

        assertThat(post(upload.token(), body).getStatusCode().value()).isEqualTo(204);

        ResponseEntity<byte[]> downloaded = rest.getForEntity("/api/file/" + upload.id(), byte[].class);
        assertThat(downloaded.getStatusCode().value()).isEqualTo(200);
        assertThat(downloaded.getBody()).isEqualTo(body);
    }

    @Test
    void refusesUploadWithoutToken() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        ResponseEntity<String> answer = rest.exchange("/api/file", HttpMethod.POST,
                new HttpEntity<>(new byte[10], headers), String.class);

        assertThat(answer.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void refusesContentLengthOverWhatTheTokenDeclared() {
        Upload upload = files.issue(room(), DEVICE, 10, 3600L);

        assertThat(post(upload.token(), new byte[5000]).getStatusCode().value()).isEqualTo(413);
        assertThat(blobDirNames()).isEmpty();
    }

    @Test
    void cutsChunkedBodyOverTheHardLimit() throws IOException {
        // Критерий фазы: файл больше потолка отбивается сервером даже тогда, когда
        // Content-Length подделан — а при chunked-кодировании его нет вовсе, и верить
        // заголовку было бы не во что.
        long limit = files.maxBytes();
        Upload upload = files.issue(room(), DEVICE, limit, 3600L);

        String status = postChunked(upload.token(), limit + 1024);

        if (status != null) {
            assertThat(status).contains("413");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM file", Integer.class))
                .as("строки нет: тело не дочитано")
                .isZero();
        assertThat(blobDirNames())
                .as("на диске не осталось ни блоба, ни куска")
                .isEmpty();
    }

    @Test
    void acceptsBodyExactlyAtTheDeclaredSize() throws IOException {
        long limit = files.maxBytes();
        Upload upload = files.issue(room(), DEVICE, limit, 3600L);

        String status = postChunked(upload.token(), limit);

        assertThat(status).contains("204");
        assertThat(files.find(upload.id())).isNotNull();
        assertThat(Files.size(DATA_DIR.resolve("blobs").resolve(upload.id()))).isEqualTo(limit);
    }

    @Test
    void answersUnknownAndExpiredTheSameWay() {
        Upload upload = files.issue(room(), DEVICE, 10, 60L);
        assertThat(post(upload.token(), new byte[10]).getStatusCode().value()).isEqualTo(204);
        assertThat(rest.getForEntity("/api/file/" + upload.id(), byte[].class).getStatusCode().value())
                .as("пока файл жив, он отдаётся — иначе одинаковость ответов ничего не значит")
                .isEqualTo(200);

        jdbc.update("UPDATE file SET expires_at = 1 WHERE id = ?", upload.id());

        ResponseEntity<byte[]> expired = rest.getForEntity("/api/file/" + upload.id(), byte[].class);
        ResponseEntity<byte[]> unknown = rest.getForEntity("/api/file/такого-нет", byte[].class);

        assertThat(expired.getStatusCode()).isEqualTo(unknown.getStatusCode());
        assertThat(expired.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void refusesIdThatClimbsOutOfTheBlobDirectory() {
        Upload upload = files.issue(room(), DEVICE, 10, 3600L);
        assertThat(post(upload.token(), new byte[10]).getStatusCode().value()).isEqualTo(204);
        assertThat(rest.getForEntity("/api/file/" + upload.id(), byte[].class).getStatusCode().value())
                .as("обычное имя эндпоинт отдаёт — значит отказ ниже про имя, а не про отсутствие ручки")
                .isEqualTo(200);

        ResponseEntity<String> answer = rest.getForEntity("/api/file/..%2F..%2Fmessenger.db", String.class);

        assertThat(answer.getStatusCode().value()).isIn(400, 404);
        assertThat(answer.getBody() == null ? "" : answer.getBody()).doesNotContain("SQLite");
    }

    private ResponseEntity<String> post(String token, byte[] body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.set("X-Upload-Token", token);
        return rest.exchange("/api/file", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    /**
     * Тело без Content-Length, кусками. Сервер вправе оборвать соединение на пороге,
     * поэтому и запись, и чтение ответа могут не дойти до конца.
     *
     * @return строка статуса либо {@code null}, если ответа не дождались
     */
    private String postChunked(String token, long bytes) throws IOException {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(("""
                    POST /api/file HTTP/1.1\r
                    Host: localhost:%d\r
                    X-Upload-Token: %s\r
                    Content-Type: application/octet-stream\r
                    Transfer-Encoding: chunked\r
                    Connection: close\r
                    \r
                    """.formatted(port, token)).getBytes(StandardCharsets.ISO_8859_1));

            byte[] chunk = new byte[64 * 1024];
            try {
                long left = bytes;
                while (left > 0) {
                    int size = (int) Math.min(chunk.length, left);
                    out.write(Integer.toHexString(size).getBytes(StandardCharsets.ISO_8859_1));
                    out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
                    out.write(chunk, 0, size);
                    out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
                    left -= size;
                }
                out.write("0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
            } catch (IOException e) {
                // Сервер закрыл приём — это и есть обрыв на пороге.
            }

            try {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
                return reader.readLine();
            } catch (IOException e) {
                return null;
            }
        }
    }

    private static Room room() {
        return new Room(ROOM, 1, 3600, 3600, 1, 0);
    }

    private List<String> blobDirNames() {
        try (var paths = Files.list(DATA_DIR.resolve("blobs"))) {
            return paths.map(path -> path.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
