/**
 * Шифрование вложений.
 *
 * У каждого файла свой случайный ключ, а не ключ комнаты. Ключ вместе с именем и
 * MIME-типом едет внутри сообщения, которое и так зашифровано room_key. Отсюда три
 * следствия сразу: файл переживает ротацию эпох, его можно отдать одноразовой ссылкой
 * не выдавая ключ комнаты, а удаление сообщения обесценивает блоб, даже если тот ещё лежит.
 *
 * secretstream, а не один crypto_box: у него чанки и метка последнего, поэтому обрезанный
 * файл не притворится целым. Двадцать мегабайт помещаются в память целиком — потоковой
 * расшифровки и Service Worker при таком потолке не нужно.
 */

/** Чанк открытого текста. 256 КБ — 80 чанков на предельный файл, накладные ~1,4 КБ. */
export const CHUNK = 256 * 1024;

/**
 * Потолок открытого текста, 20 МБ.
 *
 * Проверка здесь — только для интерфейса: настоящий потолок держит сервер, в байтах
 * шифротекста. Клиентская никого не останавливает, она лишь избавляет от бессмысленной
 * загрузки, которую всё равно отобьют.
 */
export const MAX_PLAINTEXT = 20 * 1024 * 1024;

export function encryptFile(plaintext) {
    const key = sodium.crypto_secretstream_xchacha20poly1305_keygen();
    const push = sodium.crypto_secretstream_xchacha20poly1305_init_push(key);

    const parts = [push.header];
    // Пустой файл тоже даёт один чанк: без него нет метки конца, и поток некому закрыть.
    for (let at = 0; at < plaintext.length || at === 0; at += CHUNK) {
        const slice = plaintext.slice(at, at + CHUNK);
        const last = at + CHUNK >= plaintext.length;
        parts.push(sodium.crypto_secretstream_xchacha20poly1305_push(
            push.state, slice, null,
            last
                ? sodium.crypto_secretstream_xchacha20poly1305_TAG_FINAL
                : sodium.crypto_secretstream_xchacha20poly1305_TAG_MESSAGE));
        if (last) break;
    }

    return { key, ciphertext: concat(parts) };
}

export function decryptFile(ciphertext, key) {
    const headerBytes = sodium.crypto_secretstream_xchacha20poly1305_HEADERBYTES;
    const step = CHUNK + sodium.crypto_secretstream_xchacha20poly1305_ABYTES;

    if (ciphertext.length < headerBytes) {
        throw new Error('файл повреждён: нет заголовка');
    }
    const pull = sodium.crypto_secretstream_xchacha20poly1305_init_pull(
        ciphertext.slice(0, headerBytes), key);

    const parts = [];
    let closed = false;
    for (let at = headerBytes; at < ciphertext.length; at += step) {
        const result = sodium.crypto_secretstream_xchacha20poly1305_pull(
            pull, ciphertext.slice(at, at + step));
        if (!result) {
            throw new Error('файл повреждён или ключ не от него');
        }
        parts.push(result.message);
        if (result.tag === sodium.crypto_secretstream_xchacha20poly1305_TAG_FINAL) {
            closed = true;
            break;
        }
    }

    // Обрыв на середине без метки конца — это не «почти целый файл», а другой файл.
    if (!closed) {
        throw new Error('файл обрезан: конец потока не найден');
    }
    return concat(parts);
}

function concat(parts) {
    const total = parts.reduce((sum, part) => sum + part.length, 0);
    const out = new Uint8Array(total);
    let at = 0;
    for (const part of parts) {
        out.set(part, at);
        at += part.length;
    }
    return out;
}
