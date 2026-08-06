package org.anteroom.message;

/**
 * Строка ленты как её видит сервер: непрозрачный блоб, отправитель, эпоха и дедлайн.
 * Ни текста, ни ключей здесь нет и быть не может.
 */
public record StoredMessage(long id, String sender, int epoch, byte[] ciphertext, long expiresAt) {
}
