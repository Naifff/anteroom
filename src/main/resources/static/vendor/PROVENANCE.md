# Происхождение вендоренных файлов

Забирается из npm вручную и кладётся в репозиторий. Не с CDN: код, который шифрует
переписку, должен приезжать с того же сервера, что и всё остальное, иначе публикация
хэша релиза ничего не доказывает.

| Файл | Пакет | Версия | Лицензия |
|---|---|---|---|
| `libsodium-sumo.js` | `libsodium-sumo` | 0.8.4 | ISC |
| `libsodium-wrappers-sumo.js` | `libsodium-wrappers-sumo` | 0.8.4 | ISC |
| `bip39-english.js` | `bitcoin/bips`, `bip-0039/english.txt` | — | MIT |
| `qrcode-generator.js` | `qrcode-generator` | 2.0.4 | MIT |
| `jsQR.js` | `jsqr` | 1.4.0 | Apache-2.0 |

Тексты лицензий: `LICENSE.libsodium.js`, `LICENSE.jsQR`. У `qrcode-generator` отдельного
файла лицензии в пакете нет, текст и копирайт Kazuhiko Arase лежат в шапке самого
`qrcode-generator.js` — не срезать при правках.

У списка слов отдельного файла лицензии тоже нет: он часть самого BIP-39, а тот объявляет
лицензию в собственной шапке — `License: MIT` в преамбуле `bip-0039.mediawiki`. Отдельного
копирайта на `english.txt` в репозитории `bitcoin/bips` не выставлено.

## libsodium

Сборка `sumo` — не «на всякий случай»: в обычной нет `crypto_pwhash` (Argon2id для
`key.enc` и для кода переноса) и части примитивов, нужных по фазе 3.

Подключается двумя тегами `<script>` строго в этом порядке, без сборщика: первый файл
кладёт в `window.libsodium` рантайм, второй читает его и отдаёт `window.sodium`. Дальше —
`await sodium.ready` перед первым вызовом.

## QR

Два разных пакета: `jsQR` только читает, `qrcode-generator` только рисует. Оба обычные
скрипты, дают `window.jsQR` и `window.qrcode`.

Вместе они весят около 314 КБ, поэтому страница подтягивает их **по нажатию**, а не при
загрузке: большинство ключ никогда не переносит.

## Список слов BIP-39

Порядок слов значим — индекс это одиннадцать бит seed. Любая правка списка ломает все
ранее выданные ключи. Взят из `bitcoin/bips`, английский.

## Проверка

Целостность npm-архивов на момент вендоринга:

```
libsodium-sumo-0.8.4.tgz           sha512-TMtHShQfVVsaxDygyapvUC3o7YsPgXa/hRWeIgzyFz6w5k/1hirGptCxp1U7XwW3rCskaTTYKgV10v86UiGgNw==
libsodium-wrappers-sumo-0.8.4.tgz  sha512-ql7hcgulKZ3ekfa2DGAogcCKsWU0diA/0nArz1CFzh93WQdb46/Kj18ka/Hifq6uA3Ush34Pc6vU/6HXeRwUkg==
jsqr-1.4.0.tgz                     sha512-dxLob7q65Xg2DvstYkRpkYtmKm2sPJ9oFhrhmudT1dZvNFFTlroai3AWSpLey/w5vMcLBXRgOJsbXpdN9HzU/A==
qrcode-generator-2.0.4.tgz         sha512-mZSiP6RnbHl4xL2Ap5HfkjLnmxfKcPWpWe/c+5XxCuetEenqmNFf1FH/ftXPCtFG5/TDobjsjz6sSNL0Sr8Z9g==
```

`english.txt`: `sha256 2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda`

SHA-256 самих файлов:

```
a3d0b7eb2c24ba6b5401d88717e65bc6a92786d211bc8b91ed746d24226180f3  libsodium-sumo.js
4e2c5442899321d7d9aea69bef71a3c24a6eab5911b74a5c1ad8bc8c733b0a15  libsodium-wrappers-sumo.js
bc40c8a15196236b2314db0856f72ca0b49980cd5413b8c852a7349f5fee0859  jsQR.js
79ec86f82856005b1c887905cfccfcfbec3821ca61c7fd5a952faa5f778f791c  qrcode-generator.js
```

Пересчитать: `shasum -a 256 src/main/resources/static/vendor/*.js`
