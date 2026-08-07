/**
 * Личные сообщения: рассылка на всю колоду.
 *
 * Текст шифруется случайным ключом один раз, а ключ кладётся в 52 конверта по числу мест.
 * В слоте получателя настоящий sealed box, в остальных случайные байты той же длины. Вывод
 * sealed box неотличим от случайного для того, у кого нет ключа, — поэтому сервер не знает,
 * кому адресовано, и участники не знают тоже.
 *
 * Три правила, нарушение любого раскрывает получателя:
 * слотов всегда ровно 52, включая места выбывших; все слоты одной длины; ни отправителя,
 * ни получателя нет снаружи шифротекста.
 *
 * Свой слот клиент пробует один, по номеру своей карты, а не перебирает 52.
 */

export const DECK = 52;

/** Слот: sealed box над 32-байтовым ключом сообщения, то есть 32 + crypto_box_SEALBYTES. */
export const SLOT = 32 + 48;

export const ENVELOPES = DECK * SLOT;

/**
 * Собирает колоду конвертов.
 *
 * Настоящих конвертов два: получателю и себе. Иначе отправитель не прочитает собственную
 * переписку — своего слота у него бы не было. Снаружи два настоящих неотличимы от нуля
 * настоящих: для того, у кого нет ключа, всё это случайные байты.
 *
 * @param messageKey ключ сообщения, 32 байта
 * @param recipients массив пар [номер карты, публичный ключ переписки]
 */
export function packEnvelopes(messageKey, recipients) {
    // Сначала вся колода случайными байтами, потом настоящие поверх. Порядок важен:
    // забыть заполнить остаток означало бы отдать серверу колоду нулей с одним конвертом.
    const out = sodium.randombytes_buf(ENVELOPES);
    for (const [card, publicKey] of recipients) {
        if (card < 0 || card >= DECK) {
            throw new Error('x.bad-card');
        }
        out.set(sodium.crypto_box_seal(messageKey, publicKey), card * SLOT);
    }
    return out;
}

/** Одна попытка по номеру своей карты. Не подошло — сообщение не нам, и это всё, что мы знаем. */
export function openSlot(envelopes, card, dmKeys) {
    if (envelopes.length !== ENVELOPES || card < 0 || card >= DECK) {
        return null;
    }
    try {
        return sodium.crypto_box_seal_open(
            envelopes.slice(card * SLOT, (card + 1) * SLOT), dmKeys.publicKey, dmKeys.privateKey);
    } catch {
        return null;
    }
}

/**
 * Что подписывает отправитель.
 *
 * Комната и эпоха входят в подпись, чтобы её нельзя было перенести: без них подписанный
 * текст остаётся действительным в другой комнате и после ротации ключа.
 */
export function signedPayload(roomId, epoch, toCard, body) {
    return sodium.from_string(`${roomId}\n${epoch}\n${toCard}\n${body}`);
}
