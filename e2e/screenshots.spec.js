import { test, expect } from '@playwright/test';
import { ownerInviteLink, waitForRoom, openSheet } from './helpers.js';

/*
 * Снимки для README. Помечены @screenshot и в обычный прогон не попадают — они ничего
 * не проверяют, а рисуют. Смысл делать их тестом, а не руками: интерфейс меняется,
 * и снятые вручную картинки устаревают молча. Здесь они пересоздаются одной командой:
 *
 *     npx playwright test --grep @screenshot
 */
test.describe.configure({ mode: 'serial' });

const SHOT = { path: undefined, animations: 'disabled' };

test('@screenshot лента, шторка и личное', async ({ browser }) => {
    const owner = await browser.newContext({ viewport: { width: 420, height: 860 } });
    const guest = await browser.newContext({ viewport: { width: 420, height: 860 } });
    const first = await owner.newPage();
    const second = await guest.newPage();

    await first.goto(ownerInviteLink());
    await waitForRoom(first);

    await openSheet(first);
    await first.click('#makeInvite');
    await expect(first.locator('#inviteLink')).not.toBeEmpty();
    const link = await first.locator('#inviteLink').inputValue();
    await first.click('#closeSheet');

    await second.goto(link);
    await waitForRoom(second);

    // Разговор на двоих, чтобы в ленте были и свои, и чужие реплики.
    for (const [page, text] of [
        [first, 'The room key sits on the server wrapped under your X25519. It is not in the link.'],
        [second, 'And the card? I never picked one.'],
        [first, 'It comes out of your key: HMAC(room, pubkey). You cannot pick it, so you cannot forge it.'],
        [second, 'Which is why no room ever has two of the same.'],
    ]) {
        await page.fill('#text', text);
        await page.click('#send');
        await page.waitForTimeout(120);
    }
    await expect(first.locator('.msg')).toHaveCount(4);

    await first.screenshot({ ...SHOT, path: 'docs/screenshots/feed.png' });

    await openSheet(first);
    // Шторка прокручена от прошлых действий, а показать нужно верх: отпечаток и экспорт.
    await first.locator('#sheet').evaluate((node) => { node.scrollTop = 0; });
    await first.waitForTimeout(100);
    await first.screenshot({ ...SHOT, path: 'docs/screenshots/sheet.png' });
    await first.click('#closeSheet');

    // Личное: нить с настоящим разговором. Пустой список карточек ничего не показывает,
    // а весь смысл вкладки — в том, что сервер не знает, кому это адресовано.
    await second.click('#tabDm');
    await second.click('.thread:not([disabled])');
    await second.fill('#text', 'Can the server tell this one is for you?');
    await second.click('#send');
    await second.waitForTimeout(300);

    await first.click('#tabDm');
    await first.click('.thread:not([disabled])');
    await first.fill('#text', 'No. It goes to all 52 slots — 51 of them are random bytes.');
    await first.click('#send');
    await first.waitForTimeout(400);

    await second.screenshot({ ...SHOT, path: 'docs/screenshots/direct.png' });

    await owner.close();
    await guest.close();
});
