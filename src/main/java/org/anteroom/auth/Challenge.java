package org.anteroom.auth;

/**
 * Вызов на вход: случайный nonce и подпись сервера под ним.
 *
 * <p>Секрета здесь нет — nonce одноразовый и публичный, подпись под ним тоже. Поэтому
 * обе величины спокойно едут параметрами апгрейда, а не во фрагменте.
 */
public record Challenge(String nonce, String serverSignature, String serverPublicKey) {
}
