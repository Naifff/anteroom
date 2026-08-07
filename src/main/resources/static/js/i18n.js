/**
 * Язык интерфейса.
 *
 * Английский по умолчанию, русский переключением. Выбор лежит в localStorage, а не в
 * IndexedDB рядом с ключом: это не секрет, и терять его вместе с личностью незачем.
 *
 * <p><b>Автоопределения по языку браузера нет намеренно.</b> Оно превратило бы одну и ту же
 * ссылку в две разные страницы у двух людей и сделало бы отчёты об ошибках непроверяемыми:
 * «у меня написано другое» без способа узнать, что именно. Язык меняется одной кнопкой
 * в шторке.
 *
 * <p><b>Отказы сервера и модулей приезжают кодами, а не текстом.</b> Строка на языке сервера
 * непереводима в браузере, а сравнение с ней в коде (было: `reason === 'нужно приглашение'`)
 * ломается от правки формулировки — молча, потому что ветка просто перестаёт срабатывать.
 * Коды переводятся здесь; незнакомый код показывается как есть, а не прячется.
 */

const DICT = {
  en: {
    'app.title': 'Anteroom',
    'lang.head': 'Language',

    'tab.room': 'Feed',
    'tab.dm': 'Direct',
    'back.aria': 'Back',
    'composer.placeholder': 'Message',
    'composer.placeholder.dm': 'Write privately',
    'ttl.aria': 'Message lifetime',
    'attach.aria': 'Attach a file',
    'send.aria': 'Send',
    'status.loading': 'loading…',

    'sheet.title': 'Your key',
    'sheet.close': 'Close',
    'ios.hint': 'Safari on iOS clears site data after about a week without a visit, and the key '
      + 'goes with it. Add the page to your home screen — sites installed that way are exempt.',

    'export.head': 'Export',
    'export.why': 'Do this now. Without an export, losing the browser means losing access to '
      + 'every room: the server keeps neither the key nor any way to derive it.',
    'words.show': 'Show 24 words',
    'words.hide': 'Hide',
    'words.countdown': 'hides in {left} s',
    'words.why': 'Copy them onto paper in order. Do not photograph them: the picture goes to the cloud.',

    'keyfile.save': 'Download key.enc',
    'keyfile.passphrase': 'Passphrase',
    'keyfile.encrypt': 'Encrypt and download',
    'keyfile.why': 'The file is useless without the passphrase, and the passphrase without the '
      + 'file. Argon2id encryption takes a second or two and wants memory.',
    'keyfile.short': 'a passphrase under eight characters does not protect the file',
    'keyfile.working': 'encrypting…',
    'keyfile.saved': 'file downloaded',
    'keyfile.failed': 'could not encrypt: {error}',

    'transfer.show': 'Move to another device',
    'transfer.hide': 'Hide the code',
    'transfer.aria': 'Transfer code',
    'transfer.why': 'On the new device open this same address, "Scan a code", and say the six '
      + 'digits out loud. They are not in the code itself: someone who photographed your screen '
      + 'over your shoulder opens nothing without them.',
    'transfer.preparing': 'preparing the code…',
    'transfer.countdown': 'the code goes dark in {left} s',
    'transfer.expired': 'the code went dark, press again for a new one',

    'scan.start': 'Scan a code',
    'scan.cancel': 'Cancel',
    'scan.code': 'Six digits from the old screen',
    'scan.requesting': 'requesting the camera…',
    'scan.insecure': 'camera unavailable: needs https or localhost',
    'scan.aim': 'point the camera at the code on the old device',
    'scan.found': 'code found. say the six digits from the old screen',
    'scan.failed': 'camera did not open: {error}',
    'scan.checking': 'checking the code…',

    'import.head': 'I already have a key',
    'import.why': 'Importing replaces the key on this device. The previous identity, unless it '
      + 'was exported, is lost for good.',
    'import.words': '24 words separated by spaces',
    'import.pass': 'Passphrase for the file',
    'import.do': 'Replace the key',
    'import.checking': 'checking…',
    'import.nothing': 'nothing to import: type the words or choose a file',
    'import.done': 'key replaced',

    'invite.head': 'Invite',
    'invite.why': 'Pass the link on in full and over a channel you trust: until it is redeemed it '
      + 'opens the room to anyone who opens it. After that it becomes useless, even to whoever kept a copy.',
    'invite.make': 'Create a link',

    'once.head': 'One-time note',
    'once.why': 'The text goes to the server encrypted; the key stays in the link and never '
      + 'reaches it. The first person to press the button on the note page sees the text — and '
      + 'the note is gone. That person may not be the one you meant.',
    'once.placeholder': 'What to show exactly once',
    'once.make': 'Create a link',

    'moder.head': 'Room members',
    'moder.why': 'Removing someone raises the key epoch: what comes next they cannot read. '
      + 'Everything they read before stays with them — the past cannot be re-encrypted. Their '
      + 'card does not return to the deck.',
    'moder.kick': 'Remove',
    'moder.confirm': 'Remove {card}? They cannot be given that card back.',

    'wipe.head': 'Erase the conversation',
    'wipe.why': 'The server immediately deletes every message, note and attachment in this room. '
      + 'Anyone with the tab open sees the feed empty out. Copies made before that are unaffected.',
    'wipe.do': 'Erase everything',
    'wipe.confirm': 'Erase every message, note and attachment in this room? This cannot be undone.',
    'wipe.done': 'conversation erased',

    'card.j': 'J',
    'card.q': 'Q',
    'card.k': 'K',
    'card.a': 'A',

    'ttl.300': '5 min',
    'ttl.3600': '1 h',
    'ttl.86400': '24 h',
    'ttl.172800': '48 h',
    'ttl.432000': '5 d',

    'size.b': '{n} B',
    'size.kb': '{n} KB',
    'size.mb': '{n} MB',

    'msg.nokey': 'no room key — nothing to decrypt with',
    'msg.undecryptable': 'does not decrypt: key from another epoch or another room',
    'msg.version': 'message format {got}, this build only knows {known}',

    'file.warn': 'nobody checked this file: the server sees only ciphertext, and virus scanning '
      + 'is impossible in principle with end-to-end encryption',
    'file.get': 'Download',
    'file.downloading': 'downloading…',
    'file.gone': 'the file is gone: it expired',
    'file.notserved': 'the server did not hand over the file',
    'file.toobig': '{name} is over 20 MB — the server will not take it',
    'file.encrypting': '{name}: encrypting…',
    'file.sending': '{name}: sending…',
    'file.refused.big': 'file larger than the server accepts',
    'file.refused': 'the server did not accept the file',

    'work.computing': 'computing the proof…',

    'dm.nokey': 'this member has no conversation key yet — they have not opened the tab',
    'dm.alone': 'Nobody in the room but you.',
    'dm.you': 'you: ',
    'dm.me': 'you',
    'dm.never': 'has not opened the tab yet — cannot be written to',
    'dm.write': 'write',
    'dm.empty': 'Nothing here yet. Everything you write is seen by this member alone — the '
      + 'server does not even learn who it is addressed to.',
    'dm.thread': 'Direct · {card}',

    'deck.spent': 'the deck is spent — the room takes nobody else',
    'key.pending': 'the room key has not been handed out yet — ask a member to open the tab',
    'auth.throttled': 'too many login attempts',
    'auth.nochallenge': 'the server issued no challenge',
    'auth.unsigned': 'the server did not sign its own challenge',
    'auth.serverchanged': 'the server key changed since last login — check that this is your server',
    'status.retry': '{error}, retrying in 5 s',
    'status.open': 'the room is invitation-only; the server still accepts any device',
    'link.lost': 'connection lost, reconnecting…',
    'link.down': 'connection lost',

    'once.title': 'One-time note',
    'once.lead': 'The content opens once and disappears from the server at that moment. There is '
      + 'no second try — not for you, and not for whoever you forward this link to.',
    'once.warn': 'The first person may not be the intended one: one-time means "exactly once", '
      + 'not "exactly to the person it was meant for".',
    'once.open': 'Open and destroy',
    'once.opening': 'opening…',
    'once.done': 'The note is destroyed on the server. This is its only copy — close the tab and it is gone.',
    'once.gone': 'no such note: it was already opened, it expired, or the link is wrong',
    'once.incomplete': 'the link is incomplete: it carries no key for the note',

    'e.invite-required': 'an invitation is required',
    'e.deck-spent': 'the deck is spent — the room takes nobody else',
    'e.no-access': 'no access to that room',
    'e.self-remove': 'you cannot remove yourself',
    'e.work-rejected': 'proof not accepted — solve the puzzle again',
    'e.too-often': 'too often — wait and retry',
    'e.bad-frame': 'the frame does not parse',
    'e.unknown-op': 'unknown operation',
    'e.invite-invalid': 'the invitation is not valid',
    'e.file-too-large': 'the attachment is larger than the server accepts',
    'e.room-full': 'the room is out of space for files',
    'e.disk-full': 'the server is out of space for files',
    'e.upload-refused': 'upload pass not accepted',
    'e.dm-disabled': 'direct messages are disabled in this room',
    'e.bad-envelopes': 'malformed envelopes',
    'e.message-too-large': 'the message is larger than the server accepts',
    'e.not-a-member': 'that device is not in the room',
    'e.ttl-too-short': 'that lifetime is too short to mean anything',

    'x.bad-card': 'card outside the deck',
    'x.keyfile-short': 'not a key file: too short',
    'x.keyfile-alien': 'not a key file',
    'x.keyfile-version': 'key file of a version this build does not know',
    'x.keyfile-locked': 'does not open: wrong passphrase or a damaged file',
    'x.attach-noheader': 'file damaged: no header',
    'x.attach-corrupt': 'file damaged, or the key is not its key',
    'x.attach-truncated': 'file truncated: end of stream not found',
    'x.seed-size': 'the seed must be 32 bytes',
    'x.transfer-alien': 'not a transfer code',
    'x.transfer-version': 'transfer code of a version this build does not know',
    'x.transfer-expired': 'transfer code expired: show a new one on the old device',
    'x.transfer-locked': 'does not open: wrong code or a damaged QR',
    'x.words-count': 'twenty-four words are needed',
    'x.words-unknown': 'the word "{word}" is not in the list',
    'x.words-checksum': 'checksum does not match: check the order and the spelling',
  },
  ru: {
    'app.title': 'Вылазка',
    'lang.head': 'Язык',

    'tab.room': 'Лента',
    'tab.dm': 'Личное',
    'back.aria': 'Назад',
    'composer.placeholder': 'Сообщение',
    'composer.placeholder.dm': 'Написать лично',
    'ttl.aria': 'Срок жизни',
    'attach.aria': 'Вложить файл',
    'send.aria': 'Отправить',
    'status.loading': 'загрузка…',

    'sheet.title': 'Ваш ключ',
    'sheet.close': 'Закрыть',
    'ios.hint': 'Safari на iOS чистит данные сайта примерно через неделю без визита, и ключ '
      + 'пропадёт. Добавьте страницу на домашний экран — на установленные так сайты это не действует.',

    'export.head': 'Экспорт',
    'export.why': 'Сделайте это сейчас. Без экспорта потеря браузера — потеря доступа ко всем '
      + 'комнатам: сервер не хранит ни ключа, ни способа его вывести.',
    'words.show': 'Показать 24 слова',
    'words.hide': 'Спрятать',
    'words.countdown': 'спрячется через {left} с',
    'words.why': 'Перепишите на бумагу по порядку. Не фотографируйте: снимок уедет в облако.',

    'keyfile.save': 'Скачать key.enc',
    'keyfile.passphrase': 'Парольная фраза',
    'keyfile.encrypt': 'Зашифровать и скачать',
    'keyfile.why': 'Файл без фразы бесполезен, фраза без файла — тоже. Шифрование Argon2id '
      + 'занимает секунду-другую и требует памяти.',
    'keyfile.short': 'фраза короче восьми знаков не защищает файл',
    'keyfile.working': 'шифрую…',
    'keyfile.saved': 'файл скачан',
    'keyfile.failed': 'не зашифровалось: {error}',

    'transfer.show': 'Перенести на устройство',
    'transfer.hide': 'Убрать код',
    'transfer.aria': 'Код переноса',
    'transfer.why': 'На новом устройстве откройте этот же адрес, «Сканировать код», и назовите '
      + 'шесть цифр голосом. В самом коде их нет: снявший экран через плечо без них ничего не откроет.',
    'transfer.preparing': 'готовлю код…',
    'transfer.countdown': 'код погаснет через {left} с',
    'transfer.expired': 'код погас, нажмите ещё раз для нового',

    'scan.start': 'Сканировать код',
    'scan.cancel': 'Отменить',
    'scan.code': 'Шесть цифр со старого экрана',
    'scan.requesting': 'запрашиваю камеру…',
    'scan.insecure': 'камера недоступна: нужен https или localhost',
    'scan.aim': 'наведите камеру на код со старого устройства',
    'scan.found': 'код найден. назовите шесть цифр со старого экрана',
    'scan.failed': 'камера не открылась: {error}',
    'scan.checking': 'проверяю код…',

    'import.head': 'У меня уже есть ключ',
    'import.why': 'Импорт заменит ключ на этом устройстве. Прежняя личность, если она не '
      + 'выгружена, будет потеряна безвозвратно.',
    'import.words': '24 слова через пробел',
    'import.pass': 'Фраза от файла',
    'import.do': 'Заменить ключ',
    'import.checking': 'проверяю…',
    'import.nothing': 'нечего импортировать: введите слова или выберите файл',
    'import.done': 'ключ заменён',

    'invite.head': 'Пригласить',
    'invite.why': 'Ссылку передавайте целиком и по каналу, которому доверяете: до погашения она '
      + 'открывает комнату любому, кто её открыл. После — становится бесполезной, даже если её сохранили.',
    'invite.make': 'Создать ссылку',

    'once.head': 'Одноразовая записка',
    'once.why': 'Текст уедет на сервер зашифрованным, ключ останется в ссылке и туда не попадёт. '
      + 'Первый, кто нажмёт кнопку на странице записки, увидит текст — и записка исчезнет. '
      + 'Первым может оказаться не адресат.',
    'once.placeholder': 'Что показать ровно один раз',
    'once.make': 'Создать ссылку',

    'moder.head': 'Состав комнаты',
    'moder.why': 'Исключение поднимает эпоху ключа: новое исключённый уже не прочитает. Всё, что '
      + 'он успел прочитать раньше, останется у него — перешифровать прошлое невозможно. Его карта '
      + 'в колоду не вернётся.',
    'moder.kick': 'Исключить',
    'moder.confirm': 'Исключить {card}? Вернуть участника прежней картой уже нельзя.',

    'wipe.head': 'Стереть переписку',
    'wipe.why': 'Сервер немедленно удалит все сообщения, записки и вложения этой комнаты. У тех, '
      + 'у кого вкладка открыта, лента опустеет. Копии, снятые до этого, никуда не денутся.',
    'wipe.do': 'Стереть всё',
    'wipe.confirm': 'Стереть все сообщения, записки и вложения этой комнаты? Отменить нельзя.',
    'wipe.done': 'переписка стёрта',

    'card.j': 'В',
    'card.q': 'Д',
    'card.k': 'К',
    'card.a': 'Т',

    'ttl.300': '5 мин',
    'ttl.3600': '1 ч',
    'ttl.86400': '24 ч',
    'ttl.172800': '48 ч',
    'ttl.432000': '5 сут',

    'size.b': '{n} Б',
    'size.kb': '{n} КБ',
    'size.mb': '{n} МБ',

    'msg.nokey': 'ключа комнаты нет — расшифровать нечем',
    'msg.undecryptable': 'не расшифровывается: ключ от другой эпохи или другой комнаты',
    'msg.version': 'сообщение формата {got}, эта сборка знает только {known}',

    'file.warn': 'файл никто не проверял: сервер видит только шифротекст, и антивирус при '
      + 'сквозном шифровании невозможен в принципе',
    'file.get': 'Скачать',
    'file.downloading': 'качаю…',
    'file.gone': 'файла больше нет: истёк срок',
    'file.notserved': 'сервер не отдал файл',
    'file.toobig': '{name} больше 20 МБ — сервер такой не примет',
    'file.encrypting': '{name}: шифрую…',
    'file.sending': '{name}: отправляю…',
    'file.refused.big': 'файл больше, чем принимает сервер',
    'file.refused': 'сервер не принял файл',

    'work.computing': 'считаю пропуск…',

    'dm.nokey': 'у этого участника ещё нет ключа переписки — он не открывал вкладку',
    'dm.alone': 'В комнате пока никого, кроме вас.',
    'dm.you': 'вы: ',
    'dm.me': 'вы',
    'dm.never': 'ещё не открывал вкладку — написать нельзя',
    'dm.write': 'написать',
    'dm.empty': 'Здесь пока пусто. Всё, что вы напишете, увидит только этот участник — сервер '
      + 'не узнает даже, кому адресовано.',
    'dm.thread': 'Личное · {card}',

    'deck.spent': 'колода кончилась — комната больше никого не принимает',
    'key.pending': 'ключ комнаты ещё не выдан — попросите участника открыть вкладку',
    'auth.throttled': 'слишком много попыток входа',
    'auth.nochallenge': 'сервер не выдал вызов',
    'auth.unsigned': 'сервер не подтвердил вызов своей подписью',
    'auth.serverchanged': 'ключ сервера сменился с прошлого входа — проверьте, что это ваш сервер',
    'status.retry': '{error}, повтор через 5 с',
    'status.open': 'в комнату — только по приглашению; сервер пока принимает любое устройство',
    'link.lost': 'связь потеряна, переподключаюсь…',
    'link.down': 'связь потеряна',

    'once.title': 'Одноразовая записка',
    'once.lead': 'Содержимое откроется один раз и в тот же момент исчезнет с сервера. Второй '
      + 'попытки не будет — ни у вас, ни у того, кому вы перешлёте эту ссылку.',
    'once.warn': 'Первым может оказаться не адресат: одноразовость означает «ровно один раз», '
      + 'а не «ровно тому, кому предназначалось».',
    'once.open': 'Открыть и уничтожить',
    'once.opening': 'открываю…',
    'once.done': 'Записка уничтожена на сервере. Это единственная её копия — закроете вкладку, и она пропадёт.',
    'once.gone': 'записки нет: её уже открыли, срок истёк или ссылка неверна',
    'once.incomplete': 'ссылка неполная: в ней нет ключа от записки',

    'e.invite-required': 'нужно приглашение',
    'e.deck-spent': 'колода кончилась — комната больше никого не принимает',
    'e.no-access': 'нет доступа к комнате',
    'e.self-remove': 'себя исключить нельзя',
    'e.work-rejected': 'пропуск не принят — пересчитайте задачу',
    'e.too-often': 'слишком часто — подождите и повторите',
    'e.bad-frame': 'кадр не разбирается',
    'e.unknown-op': 'неизвестная операция',
    'e.invite-invalid': 'приглашение недействительно',
    'e.file-too-large': 'вложение больше, чем принимает сервер',
    'e.room-full': 'в комнате кончилось место под файлы',
    'e.disk-full': 'на сервере кончилось место под файлы',
    'e.upload-refused': 'пропуск на загрузку не принят',
    'e.dm-disabled': 'в этой комнате личные сообщения запрещены',
    'e.bad-envelopes': 'конверты не той формы',
    'e.message-too-large': 'сообщение больше, чем принимает сервер',
    'e.not-a-member': 'устройства нет в комнате',
    'e.ttl-too-short': 'такой срок жизни ничего не значит',

    'x.bad-card': 'карта вне колоды',
    'x.keyfile-short': 'это не файл ключа: слишком короткий',
    'x.keyfile-alien': 'это не файл ключа',
    'x.keyfile-version': 'файл ключа версии, которой эта сборка не знает',
    'x.keyfile-locked': 'не открывается: другая фраза или повреждённый файл',
    'x.attach-noheader': 'файл повреждён: нет заголовка',
    'x.attach-corrupt': 'файл повреждён или ключ не от него',
    'x.attach-truncated': 'файл обрезан: конец потока не найден',
    'x.seed-size': 'seed должен быть 32 байта',
    'x.transfer-alien': 'это не код переноса',
    'x.transfer-version': 'код переноса версии, которой эта сборка не знает',
    'x.transfer-expired': 'код переноса просрочен: покажите новый на старом устройстве',
    'x.transfer-locked': 'не открывается: другой код или испорченный QR',
    'x.words-count': 'нужно двадцать четыре слова',
    'x.words-unknown': 'слова «{word}» нет в списке',
    'x.words-checksum': 'контрольная сумма не сходится: проверьте порядок и написание слов',
  },
};

const STORAGE_KEY = 'anteroom.lang';
const FALLBACK = 'en';

/** Порядок задаёт порядок кнопок в шторке. */
export const LANGUAGES = [['en', 'English'], ['ru', 'Русский']];

export function currentLanguage() {
    let saved = null;
    try {
        saved = localStorage.getItem(STORAGE_KEY);
    } catch {
        // Приватный просмотр и заблокированное хранилище: язык просто не запоминается.
    }
    return DICT[saved] ? saved : FALLBACK;
}

export function setLanguage(code) {
    if (!DICT[code]) return;
    try {
        localStorage.setItem(STORAGE_KEY, code);
    } catch {
        // Не запомнилось — переживём: страница уже перерисуется на выбранном языке.
    }
}

/**
 * Строка по ключу. Подстановки — {имя}.
 *
 * Незнакомый ключ возвращается как есть: так на экран попадает `e.something-new` вместо
 * пустоты. Это уродливо и потому заметно — а молчаливая пустота выглядит как исправная
 * работа и доживает до пользователя.
 */
export function t(key, vars) {
    const table = DICT[currentLanguage()];
    let text = table[key] ?? DICT[FALLBACK][key] ?? key;
    if (vars) {
        for (const [name, value] of Object.entries(vars)) {
            text = text.split('{' + name + '}').join(value);
        }
    }
    return text;
}

/**
 * Текст пойманного исключения.
 *
 * Модули бросают код, а подробность — какое слово не подошло, какая версия файла — кладут
 * в `vars` рядом. Смысл в том, что подробность не переводится, а текст вокруг неё переводится.
 * Чужие исключения браузера проходят насквозь: их `message` не ключ, и `t` вернёт его как есть.
 */
export function translateError(failure) {
    return t(failure.message, failure.vars);
}

/**
 * Проставляет тексты в разметку. Зовётся при загрузке и после каждой смены языка,
 * поэтому обязана быть идемпотентной: правит только помеченные узлы и ничего не создаёт.
 */
export function applyStatic(root = document) {
    document.documentElement.lang = currentLanguage();

    for (const node of root.querySelectorAll('[data-i18n]')) {
        node.textContent = t(node.dataset.i18n);
    }
    for (const node of root.querySelectorAll('[data-i18n-placeholder]')) {
        node.placeholder = t(node.dataset.i18nPlaceholder);
    }
    for (const node of root.querySelectorAll('[data-i18n-aria]')) {
        node.setAttribute('aria-label', t(node.dataset.i18nAria));
    }
    for (const node of root.querySelectorAll('[data-i18n-title]')) {
        document.title = t(node.dataset.i18nTitle);
    }
}
