import { test, expect } from '@playwright/test';
import { ownerInviteLink, waitForRoom, fakeCameraShowing, stopFakeCamera, openSheet } from './helpers.js';

/*
 * Все проверки идут одной личностью в одном контексте, последовательно.
 *
 * Не ради скорости: ссылка владельца **одноразовая** и печатается ровно при первом
 * запуске с пустой базой. Второй тест, попытавшийся войти по ней же, получает
 * «приглашение недействительно» — так и выяснилось при первом прогоне.
 *
 * Личность живёт в IndexedDB, а её Playwright между контекстами не переносит
 * (storageState — это cookies и localStorage), поэтому контекст тоже один.
 */
test.describe.configure({ mode: 'serial' });

let context;
let page;

test.beforeAll(async ({ browser }) => {
    context = await browser.newContext({ permissions: ['camera'] });
    page = await context.newPage();
    await page.goto(ownerInviteLink());
    await waitForRoom(page);
});

test.afterAll(async () => {
    await context?.close();
});

/*
 * Сценарии, которые невозможно проверить из Java: они целиком живут в браузере — камера,
 * IndexedDB, libsodium, переключение языка. Java-тесты видят только шифротекст, поэтому
 * ровно эти места до сих пор проверялись руками и ровно здесь находились баги.
 */

test.describe('первый вход', () => {

    test('владелец входит по ссылке, заводит комнату и отправляет реплику', async () => {
        // Английский по умолчанию: язык не определяется по браузеру намеренно.
        await expect(page.locator('#tabRoom')).toHaveText('Feed');
        await expect(page.locator('#status')).toContainText('invitation-only');

        await page.fill('#text', 'первая реплика');
        await page.click('#send');

        const bubble = page.locator('.msg.mine .bubble').last();
        await expect(bubble).toHaveText('первая реплика');
    });

    test('длинное слово ломается по краю пузыря, а не уезжает за него', async () => {

        await page.fill('#text', 'а'.repeat(120));
        await page.click('#send');

        const overflow = await page.locator('.msg.mine .bubble').last().evaluate((node) => ({
            content: node.scrollWidth,
            box: node.clientWidth,
            feed: document.getElementById('feed').clientWidth,
        }));

        // Именно так выглядел баг: содержимое шире пузыря, пузырь шире ленты.
        expect(overflow.content).toBeLessThanOrEqual(overflow.box + 1);
        expect(overflow.box).toBeLessThanOrEqual(overflow.feed);
    });
});

test.describe('язык', () => {

    test('переключение на русский не теряет комнату и карту', async () => {

        const before = {
            room: await page.evaluate(() => location.hash),
            seats: await page.locator('#seats').textContent(),
            print: await page.locator('#meFp').textContent(),
        };
        // Масть — символ, он одинаков на обоих языках. Ранг переводится.
        const suit = before.seats.match(/[\u2660\u2663\u2666\u2665]/)[0];

        await openSheet(page);
        await page.click('#langRow button:not([disabled])');
        await waitForRoom(page);

        await expect(page.locator('#tabRoom')).toHaveText('Лента');
        expect(await page.evaluate(() => location.hash)).toBe(before.room);
        expect(await page.locator('#meFp').textContent()).toBe(before.print);

        const after = await page.locator('#seats').textContent();
        expect(after).toContain(suit);
        expect(after).toContain(before.seats.split('·')[1].trim());

        // Старшие карты переводятся намеренно: «Т» на английской раскладке не читается
        // никак. Числовые ранги одинаковы, поэтому проверяем только если ранг буквенный.
        if (/[JQKA]/.test(before.seats)) {
            expect(after).not.toBe(before.seats);
        }

        // Возвращаем английский. Прогон идёт одной личностью подряд, выбор языка живёт
        // в localStorage, и оставленный русский ломал бы следующие тесты — что и
        // случилось при первом прогоне.
        await openSheet(page);
        await page.click('#langRow button:not([disabled])');
        await waitForRoom(page);
        await expect(page.locator('#tabRoom')).toHaveText('Feed');
    });
});

test.describe('перенос личности по коду', () => {

    /*
     * Регрессия на баг, найденный живой проверкой на телефоне: сканирование срабатывало,
     * но на месте видоискателя оставался белый прямоугольник, неотличимый от отказавшей
     * камеры. Человек решал, что камера не работает, и уходил фотографировать код.
     */
    test('видоискатель показывает кадры и исчезает после распознавания', async () => {

        await openSheet(page);
        await page.click('#showQr');
        await expect(page.locator('#transferCode')).not.toBeEmpty();

        await fakeCameraShowing(page, '#qrCanvas');
        await page.click('#startScan');

        // Код найден: поле для шести цифр и замена ключа появляются только здесь.
        await expect(page.locator('#scanCode')).toBeVisible({ timeout: 20_000 });
        await expect(page.locator('#scanMsg')).toContainText('code found');

        // Белого пятна на месте камеры остаться не должно.
        await expect(page.locator('#scanBox')).toBeHidden();

        await stopFakeCamera(page);
    });

    test('неверный код отклоняется, верный заменяет ключ', async () => {
        // Argon2id на каждую попытку — это половина защиты шестизначного кода, и здесь
        // он считается дважды. Медленно намеренно: подбор должен стоить дорого.
        test.setTimeout(180_000);
        const started = Date.now();

        await openSheet(page);
        await page.click('#showQr');
        // Дождаться кода обязательно: генератор подтягивается по нажатию, и чтение
        // сразу после клика возвращает пустую строку — тогда «верный» код оказывается
        // пустым, и тест проверяет не то, что написано в его названии.
        await expect(page.locator('#transferCode')).toHaveText(/\d{3} \d{3}/);
        const code = (await page.locator('#transferCode').textContent()).replace(/\s/g, '');
        expect(code).toMatch(/^\d{6}$/);

        await fakeCameraShowing(page, '#qrCanvas');
        await page.click('#startScan');
        await expect(page.locator('#scanCode')).toBeVisible({ timeout: 20_000 });
        await stopFakeCamera(page);

        await page.fill('#scanCode', '000000');
        await page.click('#doScanImport');
        await expect(page.locator('#scanMsg')).toContainText('does not open', { timeout: 30_000 });

        await page.fill('#scanCode', code);
        await page.click('#doScanImport');

        // Успех закрывает шторку целиком — отдельного «готово» здесь нет.
        await expect(page.locator('#sheet')).toBeHidden({ timeout: 120_000 });
        console.log(`две попытки Argon2id заняли ${Math.round((Date.now() - started) / 1000)} с`);
    });
});

test.describe('двое в комнате', () => {

    test('приглашённый входит по ссылке и видит чужую реплику', async ({ browser }) => {
        const guest = await browser.newContext();
        const second = await guest.newPage();
        const first = page;

        await first.fill('#text', 'до прихода гостя');
        await first.click('#send');
        await expect(first.locator('.msg.mine .bubble').last()).toHaveText('до прихода гостя');

        await openSheet(first);
        await first.click('#makeInvite');
        await expect(first.locator('#inviteLink')).not.toBeEmpty();
        const link = await first.locator('#inviteLink').inputValue();
        await first.click('#closeSheet');

        await second.goto(link);
        await waitForRoom(second);

        // Ключ комнаты приезжает обёрткой под X25519 гостя, а не в ссылке. Гость видит
        // и то, что было написано до его прихода: эпоха ключа не менялась, а обёртку
        // ему выдали на текущую.
        await expect(second.locator('.msg .bubble', { hasText: 'до прихода гостя' }))
                .toHaveCount(1);

        await second.fill('#text', 'ответ гостя');
        await second.click('#send');
        await expect(first.locator('.msg:not(.mine) .bubble', { hasText: 'ответ гостя' }))
                .toHaveCount(1, { timeout: 15_000 });

        // Карты разные: имя выводится из ключа, выбрать его нельзя.
        const ownerCard = await first.locator('#seats').textContent();
        const guestCard = await second.locator('#seats').textContent();
        expect(ownerCard).not.toBe(guestCard);
        expect(guestCard).toContain('2/52');

        await guest.close();
    });
});
