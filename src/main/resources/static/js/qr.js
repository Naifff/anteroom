/**
 * Рисование и чтение QR.
 *
 * Внутрь кладётся base64, а не сырые байты: режим Byte у генератора прогоняет строку
 * через UTF-8, и произвольные байты в нём портятся.
 *
 * Код всегда чёрным по белому и с полем в четыре модуля вокруг. Тёмная тема тут не
 * применяется: инверсия и обрезанное поле — главная причина, по которой сканер не видит код.
 */

const QUIET_ZONE = 4;
const TARGET_SIZE = 512;

export function drawQr(canvas, bytes) {
    const payload = sodium.to_base64(bytes, sodium.base64_variants.ORIGINAL);

    // Нулевая версия — «подбери наименьшую, куда влезет». Уровень коррекции M: под
    // экраном соседнего телефона, а не под мятой бумагой.
    const code = qrcode(0, 'M');
    code.addData(payload, 'Byte');
    code.make();

    const modules = code.getModuleCount();
    const scale = Math.max(2, Math.floor(TARGET_SIZE / (modules + QUIET_ZONE * 2)));
    const size = (modules + QUIET_ZONE * 2) * scale;

    canvas.width = size;
    canvas.height = size;

    const context = canvas.getContext('2d');
    context.fillStyle = '#fff';
    context.fillRect(0, 0, size, size);
    context.fillStyle = '#000';
    for (let row = 0; row < modules; row++) {
        for (let column = 0; column < modules; column++) {
            if (code.isDark(row, column)) {
                context.fillRect(
                    (column + QUIET_ZONE) * scale,
                    (row + QUIET_ZONE) * scale,
                    scale, scale);
            }
        }
    }
}

/** Байты из кадра или {@code null}, если кода в кадре нет. */
export function readQr(image) {
    const found = jsQR(image.data, image.width, image.height);
    if (!found) {
        return null;
    }
    try {
        return sodium.from_base64(found.data, sodium.base64_variants.ORIGINAL);
    } catch {
        // В кадр попал чужой QR — ценник, ссылка, что угодно. Это не ошибка, просто не наш.
        return null;
    }
}
