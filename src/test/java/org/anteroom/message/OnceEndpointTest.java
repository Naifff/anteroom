package org.anteroom.message;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import org.anteroom.invite.InviteHash;
import org.anteroom.room.Room;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * HTTP-слой одноразовых ссылок.
 *
 * <p>Главное здесь — что открытие не происходит по GET: превью-боты мессенджеров и
 * корпоративные антивирусы ходят по ссылке раньше человека, и по GET они сожгли бы её.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = { "app.data-dir=build/test-data-once-http", "app.challenge.per-ip-limit=10000" })
class OnceEndpointTest {

    private static final Path DATA_DIR = Path.of("build/test-data-once-http");
    private static final String ROOM = "room-once";
    private static final String TOKEN = "токен-записки";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OneTimeService links;

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
    void setUp() {
        jdbc.update("DELETE FROM onetime");
        jdbc.update("DELETE FROM room WHERE id = ?", ROOM);
        jdbc.update("INSERT INTO room (id, created_at) VALUES (?, ?)", ROOM, 0);
        links.create(room(), InviteHash.of(TOKEN), "шифротекст записки".getBytes(), 3600L);
    }

    @Test
    void previewBotWalkingTheLinkDoesNotBurnIt() {
        // Критерий фазы: пересылка ссылки в мессенджер с превью её не сжигает.
        ResponseEntity<String> page = rest.getForEntity("/once", String.class);

        assertThat(page.getStatusCode().value()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM onetime", Integer.class))
                .as("обход по GET ничего не тратит")
                .isEqualTo(1);
        assertThat(open(TOKEN).getStatusCode().value())
                .as("после бота человек всё ещё открывает записку")
                .isEqualTo(200);
    }

    @Test
    void handsTheSecretToTheFirstOpenerOnly() {
        ResponseEntity<byte[]> first = open(TOKEN);

        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(first.getBody()).isEqualTo("шифротекст записки".getBytes());
        assertThat(open(TOKEN).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void answersBurnedAndUnknownTheSameWay() {
        assertThat(open(TOKEN).getStatusCode().value())
                .as("живая записка отдаётся — иначе одинаковость ответов ничего не значит")
                .isEqualTo(200);

        ResponseEntity<byte[]> burned = open(TOKEN);
        ResponseEntity<byte[]> unknown = open("никогда-не-выдавался");

        assertThat(burned.getStatusCode()).isEqualTo(unknown.getStatusCode());
        assertThat(burned.getBody()).isEqualTo(unknown.getBody());
    }

    @Test
    void keepsTheTokenOutOfTheUrl() {
        // Токен едет телом запроса. В пути он попал бы в access.log веб-сервера, и
        // одноразовость этого не отменяет: журнал переживёт сжигание ссылки.
        links.create(room(), InviteHash.of("вторая"), "вторая записка".getBytes(), 3600L);
        assertThat(open("вторая").getStatusCode().value())
                .as("телом токен работает — значит отказ ниже про адрес, а не про ручку целиком")
                .isEqualTo(200);

        ResponseEntity<String> byPath = rest.exchange("/api/once/" + TOKEN, HttpMethod.POST,
                HttpEntity.EMPTY, String.class);

        assertThat(byPath.getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM onetime", Integer.class))
                .as("записка цела: такого адреса нет")
                .isEqualTo(1);
    }

    private ResponseEntity<byte[]> open(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        return rest.exchange("/api/once", HttpMethod.POST, new HttpEntity<>(token, headers), byte[].class);
    }

    private static Room room() {
        return new Room(ROOM, 1, 3600, 3600, 1, 0);
    }
}
