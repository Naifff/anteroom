import { readFileSync } from 'node:fs';

export const SERVER_LOG = 'build/e2e-server.log';

/**
 * Ссылка владельца из журнала первого запуска.
 *
 * Печатается один раз и только пока владельца нет: в базе лежит лишь хэш, восстановить
 * её нельзя. Поэтому каталог данных перед прогоном стирается — иначе второй запуск
 * не напечатает ничего, и тесты начнут зависеть от порядка запусков.
 */
export function ownerInviteLink() {
    const log = readFileSync(SERVER_LOG, 'utf8');
    const found = log.match(/(http:\/\/\S+\/join#\S+)/);
    if (!found) {
        throw new Error('в журнале нет ссылки владельца — база была не пуста?');
    }
    return found[1];
}

/** Ждёт, пока сокет установится и лента оживёт. */
export async function waitForRoom(page) {
    await page.waitForFunction(() => {
        const seats = document.getElementById('seats');
        return seats && /\d+\/52/.test(seats.textContent);
    }, { timeout: 30_000 });
}

/**
 * Подменяет камеру холстом, на который перерисовывается переданный элемент.
 *
 * Статичный {@code captureStream} кадров не шлёт — поток обязан именно рисоваться,
 * иначе видео никогда не дойдёт до HAVE_ENOUGH_DATA и цикл распознавания встанет.
 */
export async function fakeCameraShowing(page, sourceSelector) {
    await page.evaluate((selector) => {
        const source = document.querySelector(selector);
        const stage = document.createElement('canvas');
        stage.width = 640;
        stage.height = 480;
        const context = stage.getContext('2d');
        window.__fakeCamera = setInterval(() => {
            context.fillStyle = '#fff';
            context.fillRect(0, 0, stage.width, stage.height);
            if (source) {
                context.drawImage(source, 80, 0, 480, 480);
            }
        }, 50);
        const stream = stage.captureStream(15);
        navigator.mediaDevices.getUserMedia = () => Promise.resolve(stream);
    }, sourceSelector);
}

export async function stopFakeCamera(page) {
    await page.evaluate(() => clearInterval(window.__fakeCamera));
}

/**
 * Открывает шторку, предварительно закрыв её, если она осталась открытой от прошлой
 * проверки: поверх страницы лежит затемнение, и оно перехватывает клики по карте.
 * Закрытие заодно гасит камеру и код переноса — то есть служит сбросом состояния.
 */
export async function openSheet(page) {
    if (await page.locator('#sheet').isVisible()) {
        await page.click('#closeSheet');
        await page.locator('#sheet').waitFor({ state: 'hidden' });
    }
    await page.click('#meChip');
    await page.locator('#sheet').waitFor({ state: 'visible' });
}
