/**
 * Seed двадцатью четырьмя словами, BIP-39.
 *
 * 256 бит seed плюс 8 бит контрольной суммы дают 264 бита, ровно 24 слова по 11 бит.
 * Контрольная сумма нужна не для красоты: без неё описка при переписывании с бумаги
 * молча даёт другую личность, и человек видит пустую комнату вместо ошибки.
 */

import { BIP39_ENGLISH } from '/vendor/bip39-english.js';

const WORD_COUNT = 24;
const SEED_BYTES = 32;

export function seedToWords(seed) {
    if (seed.length !== SEED_BYTES) {
        throw new Error(`seed должен быть ${SEED_BYTES} байта, а не ${seed.length}`);
    }

    const bits = toBits(seed) + checksumBits(seed);
    const words = [];
    for (let i = 0; i < WORD_COUNT; i++) {
        words.push(BIP39_ENGLISH[parseInt(bits.slice(i * 11, (i + 1) * 11), 2)]);
    }
    return words;
}

export function wordsToSeed(words) {
    const normalized = words.map((word) => word.trim().toLowerCase());
    if (normalized.length !== WORD_COUNT) {
        throw new Error(`нужно ${WORD_COUNT} слова, а не ${normalized.length}`);
    }

    let bits = '';
    for (const word of normalized) {
        const index = BIP39_ENGLISH.indexOf(word);
        if (index < 0) {
            throw new Error(`слова «${word}» нет в списке`);
        }
        bits += index.toString(2).padStart(11, '0');
    }

    const seed = new Uint8Array(SEED_BYTES);
    for (let i = 0; i < SEED_BYTES; i++) {
        seed[i] = parseInt(bits.slice(i * 8, (i + 1) * 8), 2);
    }

    if (bits.slice(SEED_BYTES * 8) !== checksumBits(seed)) {
        throw new Error('контрольная сумма не сходится: проверьте порядок и написание слов');
    }
    return seed;
}

/** Первые 8 бит SHA-256 от seed — столько же, сколько лишних бит в 24 словах. */
function checksumBits(seed) {
    return sodium.crypto_hash_sha256(seed)[0].toString(2).padStart(8, '0');
}

function toBits(bytes) {
    return Array.from(bytes, (byte) => byte.toString(2).padStart(8, '0')).join('');
}
