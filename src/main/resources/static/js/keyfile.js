/**
 * Файл ключа под парольной фразой.
 *
 * Формат: заголовок, версия, параметры Argon2id, соль, nonce, шифротекст. Параметры
 * лежат в файле, а не в коде: подняв их завтра, мы обязаны уметь открыть вчерашний файл.
 * Версия — по тому же правилу, что и для формата сообщений: без неё миграция ломает
 * ранее выданные файлы.
 *
 * Соль случайна на каждый файл. Иначе две обёртки одного seed под одной фразой совпали бы
 * побайтно, и по этому совпадению видно, что это одна личность.
 */

const MAGIC = 'ANTEROOM-KEY';
const VERSION = 1;
const SALT_BYTES = 16;

export async function encryptSeed(seed, passphrase) {
    await sodium.ready;

    const salt = sodium.randombytes_buf(SALT_BYTES);
    const opslimit = sodium.crypto_pwhash_OPSLIMIT_MODERATE;
    const memlimit = sodium.crypto_pwhash_MEMLIMIT_MODERATE;

    const key = derive(passphrase, salt, opslimit, memlimit);
    const nonce = sodium.randombytes_buf(sodium.crypto_secretbox_NONCEBYTES);
    const box = sodium.crypto_secretbox_easy(seed, nonce, key);

    const header = new Uint8Array(MAGIC.length + 1 + 4 + 4);
    const view = new DataView(header.buffer);
    header.set(sodium.from_string(MAGIC), 0);
    header[MAGIC.length] = VERSION;
    view.setUint32(MAGIC.length + 1, opslimit);
    // memlimit измеряется байтами и в 32 бита влезает: MODERATE это 256 МБ.
    view.setUint32(MAGIC.length + 5, memlimit);

    return concat(header, salt, nonce, box);
}

export async function decryptSeed(file, passphrase) {
    await sodium.ready;

    const bytes = new Uint8Array(file);
    const headerLength = MAGIC.length + 9;
    if (bytes.length < headerLength + SALT_BYTES + sodium.crypto_secretbox_NONCEBYTES) {
        throw new Error('x.keyfile-short');
    }
    if (sodium.to_string(bytes.slice(0, MAGIC.length)) !== MAGIC) {
        throw new Error('x.keyfile-alien');
    }
    if (bytes[MAGIC.length] !== VERSION) {
        throw new Error('x.keyfile-version');
    }

    const view = new DataView(bytes.buffer, bytes.byteOffset);
    const opslimit = view.getUint32(MAGIC.length + 1);
    const memlimit = view.getUint32(MAGIC.length + 5);

    let at = headerLength;
    const salt = bytes.slice(at, at += SALT_BYTES);
    const nonce = bytes.slice(at, at += sodium.crypto_secretbox_NONCEBYTES);
    const box = bytes.slice(at);

    const key = derive(passphrase, salt, opslimit, memlimit);
    try {
        return sodium.crypto_secretbox_open_easy(box, nonce, key);
    } catch {
        // Неверная фраза и порченый файл неотличимы снаружи, и это правильно:
        // различать их означало бы подсказывать перебирающему, что фраза угадана.
        throw new Error('x.keyfile-locked');
    }
}

function derive(passphrase, salt, opslimit, memlimit) {
    return sodium.crypto_pwhash(
        sodium.crypto_secretbox_KEYBYTES,
        passphrase,
        salt,
        opslimit,
        memlimit,
        sodium.crypto_pwhash_ALG_ARGON2ID13);
}

function concat(...parts) {
    const total = parts.reduce((sum, part) => sum + part.length, 0);
    const out = new Uint8Array(total);
    let at = 0;
    for (const part of parts) {
        out.set(part, at);
        at += part.length;
    }
    return out;
}
