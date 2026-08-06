package org.anteroom.file;

/**
 * Разрешение на одну загрузку.
 *
 * @param id        имя блоба и адрес скачивания; случайный, из имени файла не выводится
 * @param token     одноразовый пропуск к {@code POST /api/file}
 * @param expiresAt когда пропуск перестанет действовать
 */
public record Upload(String id, String token, long expiresAt) {
}
