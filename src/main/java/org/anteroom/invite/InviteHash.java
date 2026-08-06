package org.anteroom.invite;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * SHA-256 от токена приглашения в base64url.
 *
 * <p>Формат общий для клиента и сервера: клиент считает хэш при выпуске, сервер — при
 * погашении. Разойдутся — перестанут открываться все выданные ссылки.
 */
public final class InviteHash {

    private InviteHash() {
    }

    public static String of(String token) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("в этой JVM нет SHA-256", e);
        }
    }
}
