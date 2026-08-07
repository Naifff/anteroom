package org.anteroom.message;

/**
 * Личное сообщение так, как его видит сервер.
 *
 * <p>Ни отправителя, ни получателя: первый лежит подписанным внутри {@code ciphertext},
 * второй не существует нигде — он определяется тем, чей слот в {@code envelopes} разворачивается.
 */
public record StoredDirect(long id, byte[] ciphertext, byte[] envelopes, long expiresAt) {
}
