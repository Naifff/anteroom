package org.anteroom.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerKeyStoreTest {

    @TempDir
    Path dataDir;

    @Test
    void createsKeyWhenMissing() {
        ServerKeyStore store = new ServerKeyStore(dataDir);

        assertThat(dataDir.resolve("server.key")).exists();
        assertThat(store.publicKey()).hasSize(32);
    }

    @Test
    void createsKeyReadableOnlyByOwner() throws IOException {
        new ServerKeyStore(dataDir);

        assertThat(Files.getPosixFilePermissions(dataDir.resolve("server.key")))
                .containsExactlyInAnyOrderElementsOf(PosixFilePermissions.fromString("rw-------"));
    }

    @Test
    void keepsSameKeyAcrossRestarts() {
        // Ключ сервера переживает перезапуск, иначе клиенты, запомнившие отпечаток,
        // при каждом рестарте видят подмену сервера.
        byte[] first = new ServerKeyStore(dataDir).publicKey();

        assertThat(new ServerKeyStore(dataDir).publicKey()).isEqualTo(first);
    }

    @Test
    void refusesToStartWhenKeyIsReadableByOthers() throws IOException {
        Path key = dataDir.resolve("server.key");
        new ServerKeyStore(dataDir);
        Files.setPosixFilePermissions(key, PosixFilePermissions.fromString("rw-r--r--"));

        // Подменивший этот файл выдаёт себя за сервер при входе, поэтому мягкое
        // предупреждение тут не годится — только отказ старта.
        assertThatThrownBy(() -> new ServerKeyStore(dataDir))
                .isInstanceOf(InsecureServerKeyException.class)
                .hasMessageContaining("server.key");
    }

    @Test
    void refusesToStartWhenKeyIsWritableByGroup() throws IOException {
        Path key = dataDir.resolve("server.key");
        new ServerKeyStore(dataDir);
        Files.setPosixFilePermissions(key, PosixFilePermissions.fromString("rw-rw----"));

        assertThatThrownBy(() -> new ServerKeyStore(dataDir)).isInstanceOf(InsecureServerKeyException.class);
    }

    @Test
    void signsVerifiablyWithItsOwnKey() {
        ServerKeyStore store = new ServerKeyStore(dataDir);
        byte[] message = "вызов".getBytes();

        assertThat(Ed25519Keys.verify(store.sign(message), message, store.publicKey())).isTrue();
    }

    @Test
    void signatureDoesNotVerifyUnderAnotherKey() {
        ServerKeyStore store = new ServerKeyStore(dataDir);
        byte[] message = "вызов".getBytes();
        byte[] stranger = Ed25519Keys.rawPublicKey(Ed25519Keys.newKeyPair().getPublic());

        assertThat(Ed25519Keys.verify(store.sign(message), message, stranger)).isFalse();
    }

    @Test
    void refusesGarbageInsteadOfKeyFile() throws IOException {
        Path key = dataDir.resolve("server.key");
        Files.writeString(key, "не ключ");
        // Права закрываем сами: иначе первой сработает проверка прав, и тест проверит её,
        // а не разбор содержимого.
        Files.setPosixFilePermissions(key, PosixFilePermissions.fromString("rw-------"));

        assertThatThrownBy(() -> new ServerKeyStore(dataDir)).isInstanceOf(IllegalStateException.class);
    }
}
