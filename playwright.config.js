// @ts-check
import { defineConfig, devices } from '@playwright/test';

/*
 * Браузерные проверки того, что нельзя проверить из Java: камера, обмен ключами между
 * двумя личностями, переключение языка. Всё остальное по-прежнему тестируется на Java —
 * npm заведён только ради этих сценариев и в раздаваемый бандл не попадает.
 *
 * Сервер поднимается сам, на отдельном порту и с отдельным каталогом данных: прогон
 * обязан начинаться с пустой базы, иначе первый запуск печатает owner-инвайт, а
 * второй — нет, и тесты расходятся с самими собой.
 */
const PORT = 18099;
const DATA_DIR = 'build/e2e-data';
export const LOG = 'build/e2e-server.log';

export default defineConfig({
    testDir: './e2e',
    fullyParallel: false,
    workers: 1,
    forbidOnly: !!process.env.CI,
    retries: process.env.CI ? 1 : 0,
    reporter: process.env.CI ? [['github'], ['html', { open: 'never' }]] : [['list']],

    use: {
        baseURL: `http://localhost:${PORT}`,
        trace: 'retain-on-failure',
        screenshot: 'only-on-failure',
    },

    projects: [
        {
            name: 'chromium',
            use: {
                ...devices['Desktop Chrome'],
                // Разрешение на камеру выдаётся заранее: диалог iOS-подобного вида
                // в headless не показывается, а без разрешения getUserMedia отклоняется
                // ещё до того, как до него дойдёт подставной поток.
                permissions: ['camera'],
            },
        },
    ],

    webServer: {
        // Журнал уходит в файл: owner-инвайт первого запуска печатается в stdout
        // ровно один раз, и другого способа получить его у теста нет.
        command: `rm -rf ${DATA_DIR} && mkdir -p build && ./gradlew bootRun --args="`
            + `--app.data-dir=${DATA_DIR} --server.port=${PORT} --server.address=127.0.0.1 `
            + `--app.public-url=http://localhost:${PORT} --app.work.bits=0" 2>&1 | tee ${LOG}`,
        url: `http://localhost:${PORT}/`,
        reuseExistingServer: false,
        timeout: 180_000,
        stdout: 'pipe',
    },
});
