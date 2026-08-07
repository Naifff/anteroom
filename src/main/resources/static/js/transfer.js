/**
 * Перенос личности на другое устройство.
 *
 * В QR едет seed, зашифрованный шестизначным кодом; код показывается отдельно и
 * передаётся голосом. Без этого хватило бы снять экран через плечо.
 *
 * Шесть цифр — это меньше двадцати бит, и держится схема на двух вещах разом:
 * Argon2id на каждую попытку и две минуты жизни кода. Снявший экран получает и то,
 * и другое навсегда, поэтому короткий срок здесь не украшение, а половина защиты.
 * Показывать QR дольше нельзя.
 */

export const TRANSFER_TTL_MS = 120000;

const MAGIC = 'ANTEROOM-XFER';
const VERSION = 1;
const SALT_BYTES = 16;
const DEADLINE_BYTES = 8;

export function newTransferCode() {
    // Отбраковка хвоста, а не остаток от деления: при взятии по модулю младшие коды
    // выпадают чаще, и перебор начинают с них.
    const limit = 1000000;
    const ceiling = Math.floor(0xFFFFFFFF / limit) * limit;
    let value;
    do {
        value = new DataView(sodium.randombytes_buf(4).buffer).getUint32(0);
    } while (value >= ceiling);
    return String(value % limit).padStart(6, '0');
}

export async function packTransfer(seed, code, now) {
    await sodium.ready;

    const salt = sodium.randombytes_buf(SALT_BYTES);
    const opslimit = sodium.crypto_pwhash_OPSLIMIT_MODERATE;
    const memlimit = sodium.crypto_pwhash_MEMLIMIT_MODERATE;

    const header = new Uint8Array(MAGIC.length + 1 + DEADLINE_BYTES + 4 + 4);
    const view = new DataView(header.buffer);
    header.set(sodium.from_string(MAGIC), 0);
    header[MAGIC.length] = VERSION;
    view.setBigUint64(MAGIC.length + 1, BigInt(now + TRANSFER_TTL_MS));
    view.setUint32(MAGIC.length + 9, opslimit);
    view.setUint32(MAGIC.length + 13, memlimit);

    const key = derive(code, salt, opslimit, memlimit);
    const nonce = sodium.randombytes_buf(sodium.crypto_aead_xchacha20poly1305_ietf_NPUBBYTES);
    // Заголовок целиком идёт связанными данными: срок лежит открытым, иначе получатель
    // не поймёт, что опоздал, — и потому обязан участвовать в аутентификации, иначе его
    // правят в шестнадцатеричном редакторе и просроченный перенос оживает.
    const box = sodium.crypto_aead_xchacha20poly1305_ietf_encrypt(seed, header, null, nonce, key);

    return concat(header, salt, nonce, box);
}

export async function unpackTransfer(payload, code, now) {
    await sodium.ready;

    const bytes = new Uint8Array(payload);
    const headerLength = MAGIC.length + 1 + DEADLINE_BYTES + 8;
    if (bytes.length < headerLength + SALT_BYTES + sodium.crypto_aead_xchacha20poly1305_ietf_NPUBBYTES) {
        throw new Error('x.transfer-alien');
    }
    if (sodium.to_string(bytes.slice(0, MAGIC.length)) !== MAGIC) {
        throw new Error('x.transfer-alien');
    }
    if (bytes[MAGIC.length] !== VERSION) {
        throw new Error('x.transfer-version');
    }

    const header = bytes.slice(0, headerLength);
    const view = new DataView(bytes.buffer, bytes.byteOffset);
    const deadline = Number(view.getBigUint64(MAGIC.length + 1));
    if (now > deadline) {
        throw new Error('x.transfer-expired');
    }

    const opslimit = view.getUint32(MAGIC.length + 9);
    const memlimit = view.getUint32(MAGIC.length + 13);

    let at = headerLength;
    const salt = bytes.slice(at, at += SALT_BYTES);
    const nonce = bytes.slice(at, at += sodium.crypto_aead_xchacha20poly1305_ietf_NPUBBYTES);
    const box = bytes.slice(at);

    const key = derive(code, salt, opslimit, memlimit);
    try {
        return sodium.crypto_aead_xchacha20poly1305_ietf_decrypt(null, box, header, nonce, key);
    } catch {
        throw new Error('x.transfer-locked');
    }
}

function derive(code, salt, opslimit, memlimit) {
    return sodium.crypto_pwhash(
        sodium.crypto_aead_xchacha20poly1305_ietf_KEYBYTES,
        code,
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
