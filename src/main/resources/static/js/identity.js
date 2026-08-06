/**
 * Личность устройства: один seed на 32 байта, из него детерминированно обе пары ключей.
 *
 * Ничего отсюда не уходит на сервер. Сервер знает только публичные части, и восстановить
 * по ним seed нельзя — на этом держится обещание «потерянный ключ не восстанавливает никто,
 * включая нас».
 */

const DB_NAME = 'anteroom';
const DB_VERSION = 1;
const STORE = 'identity';
const SEED_KEY = 'seed';
const SERVER_KEY = 'server-key';

/** Домен для вывода под-seed ключа шифрования. Менять нельзя: сменится — сменится личность. */
const BOX_DOMAIN = 'box';

export function newSeed() {
    return sodium.randombytes_buf(32);
}

/**
 * Обе пары из одного seed.
 *
 * Ed25519 берётся из seed напрямую, X25519 — из под-seed BLAKE2b, где ключом выступает сам
 * seed, а сообщением домен `box`. Прямой вывод обеих пар из одного секрета связал бы подпись
 * и шифрование: утечка одного забирала бы второе.
 *
 * Детерминированность — не оптимизация. От неё зависит, что восстановление из 24 слов даёт
 * ту же личность, ту же карту и разворачивает старые wrapped_room_key.
 */
export function keysFromSeed(seed) {
    if (seed.length !== 32) {
        throw new Error(`seed должен быть 32 байта, а не ${seed.length}`);
    }

    const signing = sodium.crypto_sign_seed_keypair(seed);
    const boxSeed = sodium.crypto_generichash(32, sodium.from_string(BOX_DOMAIN), seed);
    const boxing = sodium.crypto_box_seed_keypair(boxSeed);

    return {
        signPublic: signing.publicKey,
        signSecret: signing.privateKey,
        boxPublic: boxing.publicKey,
        boxSecret: boxing.privateKey,
    };
}

export async function saveSeed(seed) {
    const db = await open();
    try {
        await run(db, 'readwrite', (store) => store.put(seed, SEED_KEY));
    } finally {
        db.close();
    }
}

export async function loadSeed() {
    const db = await open();
    try {
        const stored = await run(db, 'readonly', (store) => store.get(SEED_KEY));
        return stored === undefined ? null : new Uint8Array(stored);
    } finally {
        db.close();
    }
}

/**
 * Отпечаток сервера, запомненный при первом входе.
 *
 * Не секрет — это публичный ключ, — но живёт там же, где seed, а не в localStorage:
 * одно хранилище на всё состояние личности, и очистка данных сайта уносит его целиком.
 */
export async function saveServerKey(publicKey) {
    const db = await open();
    try {
        await run(db, 'readwrite', (store) => store.put(publicKey, SERVER_KEY));
    } finally {
        db.close();
    }
}

export async function loadServerKey() {
    const db = await open();
    try {
        const stored = await run(db, 'readonly', (store) => store.get(SERVER_KEY));
        return stored === undefined ? null : stored;
    } finally {
        db.close();
    }
}

export async function forgetSeed() {
    const db = await open();
    try {
        await run(db, 'readwrite', (store) => store.delete(SEED_KEY));
    } finally {
        db.close();
    }
}

function open() {
    return new Promise((resolve, reject) => {
        const request = indexedDB.open(DB_NAME, DB_VERSION);
        request.onupgradeneeded = () => request.result.createObjectStore(STORE);
        request.onsuccess = () => resolve(request.result);
        request.onerror = () => reject(request.error);
    });
}

function run(db, mode, action) {
    return new Promise((resolve, reject) => {
        const transaction = db.transaction(STORE, mode);
        const request = action(transaction.objectStore(STORE));
        request.onsuccess = () => resolve(request.result);
        // Ошибку берём с транзакции тоже: запись может отвалиться уже после успеха запроса,
        // например когда браузер отказал в квоте.
        transaction.onabort = () => reject(transaction.error);
        request.onerror = () => reject(request.error);
    });
}
