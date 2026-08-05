package org.anteroom.auth;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ключевая пара сервера. Ею подписывается challenge, чтобы клиент мог убедиться,
 * что разговаривает с тем же сервером, что и в прошлый раз.
 *
 * <p>Файл создаётся один раз и переживает рестарты: новая пара на каждом запуске
 * выглядела бы для клиентов как подмена сервера, и предупреждение об этом обесценилось бы
 * на второй день.
 */
public class ServerKeyStore {

    private static final Logger log = LoggerFactory.getLogger(ServerKeyStore.class);

    private static final String FILE_NAME = "server.key";
    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

    private final PrivateKey privateKey;
    private final byte[] publicKey;

    public ServerKeyStore(Path dataDir) {
        Path file = dataDir.resolve(FILE_NAME);
        try {
            if (Files.exists(file)) {
                requireOwnerOnly(file);
                String[] lines = Files.readAllLines(file).stream()
                        .filter(line -> !line.isBlank() && !line.startsWith("#"))
                        .toArray(String[]::new);
                if (lines.length != 2) {
                    throw new IllegalStateException(file + ": не похоже на ключ сервера");
                }
                this.privateKey = readPrivateKey(lines[0]);
                this.publicKey = Base64.getDecoder().decode(lines[1]);
            } else {
                KeyPair pair = Ed25519Keys.newKeyPair();
                this.privateKey = pair.getPrivate();
                this.publicKey = Ed25519Keys.rawPublicKey(pair.getPublic());
                write(file);
                log.info("Создана ключевая пара сервера, {}", file);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось прочитать " + file, e);
        }
    }

    public byte[] publicKey() {
        return publicKey.clone();
    }

    public byte[] sign(byte[] message) {
        return Ed25519Keys.sign(message, privateKey);
    }

    /**
     * Отказ старта, а не предупреждение: подменивший этот файл выдаёт себя за сервер
     * при входе, и работать дальше с открытым для чужих ключом нельзя.
     */
    private static void requireOwnerOnly(Path file) throws IOException {
        Set<PosixFilePermission> actual = Files.getPosixFilePermissions(file);
        if (!OWNER_ONLY.containsAll(actual)) {
            throw new InsecureServerKeyException(file, PosixFilePermissions.toString(actual));
        }
    }

    private static PrivateKey readPrivateKey(String base64) {
        try {
            return KeyFactory.getInstance("Ed25519")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("ключ сервера не разбирается", e);
        }
    }

    private void write(Path file) throws IOException {
        // Права ставятся при создании, а не после записи: между записью и chmod файл
        // успел бы полежать открытым.
        Files.createFile(file, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
        Files.write(file, List.of(
                Base64.getEncoder().encodeToString(privateKey.getEncoded()),
                Base64.getEncoder().encodeToString(publicKey)));
    }
}
