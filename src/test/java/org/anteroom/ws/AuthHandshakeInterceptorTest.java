package org.anteroom.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.Base64;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.anteroom.auth.Ed25519Keys;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.data-dir=build/test-data-auth")
class AuthHandshakeInterceptorTest {

    private static final Path DATA_DIR = Path.of("build/test-data-auth");
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

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

    @SuppressWarnings("unchecked")
    private Map<String, String> fetchChallenge() {
        return rest.getForObject("/api/challenge", Map.class);
    }

    private WebSocketSession connect(String nonce, String pubkey, String signature) throws Exception {
        URI uri = URI.create("ws://localhost:" + port + "/ws"
                + "?nonce=" + nonce + "&device=" + pubkey + "&signature=" + signature);
        return new StandardWebSocketClient()
                .execute(new TextWebSocketHandler(), null, uri)
                .get(5, TimeUnit.SECONDS);
    }

    private static String sign(String nonce, KeyPair device) {
        return ENCODER.encodeToString(
                Ed25519Keys.sign(Base64.getUrlDecoder().decode(nonce), device.getPrivate()));
    }

    private static String publicKeyOf(KeyPair device) {
        return ENCODER.encodeToString(Ed25519Keys.rawPublicKey(device.getPublic()));
    }

    @Test
    void servesChallengeSignedByServer() {
        Map<String, String> challenge = fetchChallenge();

        assertThat(challenge).containsKeys("nonce", "serverSignature", "serverPublicKey");
        assertThat(Ed25519Keys.verify(
                Base64.getUrlDecoder().decode(challenge.get("serverSignature")),
                Base64.getUrlDecoder().decode(challenge.get("nonce")),
                Base64.getUrlDecoder().decode(challenge.get("serverPublicKey")))).isTrue();
    }

    @Test
    void letsInDeviceWithValidSignature() throws Exception {
        KeyPair device = Ed25519Keys.newKeyPair();
        String nonce = fetchChallenge().get("nonce");

        try (WebSocketSession session = connect(nonce, publicKeyOf(device), sign(nonce, device))) {
            assertThat(session.isOpen()).isTrue();
        }
    }

    @Test
    void remembersDeviceThatSignedIn() throws Exception {
        KeyPair device = Ed25519Keys.newKeyPair();
        String nonce = fetchChallenge().get("nonce");
        String pubkey = publicKeyOf(device);

        connect(nonce, pubkey, sign(nonce, device)).close(CloseStatus.NORMAL);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM device WHERE pubkey_sign = ?", Integer.class, pubkey)).isEqualTo(1);
    }

    @Test
    void rejectsForgedSignature() {
        KeyPair device = Ed25519Keys.newKeyPair();
        KeyPair stranger = Ed25519Keys.newKeyPair();
        String nonce = fetchChallenge().get("nonce");

        assertThatThrownBy(() -> connect(nonce, publicKeyOf(device), sign(nonce, stranger)))
                .as("подпись чужим ключом не должна пускать")
                .isNotNull();
    }

    @Test
    void rejectsReplayOfUsedChallenge() throws Exception {
        KeyPair device = Ed25519Keys.newKeyPair();
        String nonce = fetchChallenge().get("nonce");
        String pubkey = publicKeyOf(device);
        String signature = sign(nonce, device);

        connect(nonce, pubkey, signature).close(CloseStatus.NORMAL);

        assertThatThrownBy(() -> connect(nonce, pubkey, signature))
                .as("тот же вызов второй раз не должен пускать")
                .isNotNull();
    }

    @Test
    void rejectsHandshakeWithoutCredentials() {
        assertThatThrownBy(() -> new StandardWebSocketClient()
                .execute(new TextWebSocketHandler(), null, URI.create("ws://localhost:" + port + "/ws"))
                .get(5, TimeUnit.SECONDS))
                .as("апгрейд без вызова и подписи не должен проходить")
                .isNotNull();
    }

    @Test
    void rejectsNonceItNeverIssued() {
        KeyPair device = Ed25519Keys.newKeyPair();
        String stranger = ENCODER.encodeToString(new byte[32]);

        assertThatThrownBy(() -> connect(stranger, publicKeyOf(device), sign(stranger, device)))
                .isNotNull();
    }
}
