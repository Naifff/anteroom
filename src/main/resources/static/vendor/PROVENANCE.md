# Происхождение вендоренных файлов

Забирается из npm вручную и кладётся в репозиторий. Не с CDN: код, который шифрует
переписку, должен приезжать с того же сервера, что и всё остальное, иначе публикация
хэша релиза ничего не доказывает.

Сборка `sumo` — не «на всякий случай»: в обычной нет `crypto_pwhash` (Argon2id для
`key.enc`) и части примитивов, нужных по фазе 3.

| Файл | Пакет | Версия |
|---|---|---|
| `libsodium-sumo.js` | `libsodium-sumo` | 0.8.4 |
| `libsodium-wrappers-sumo.js` | `libsodium-wrappers-sumo` | 0.8.4 |

Лицензия ISC, текст в `LICENSE.libsodium.js`.

## Как подключается

Двумя тегами `<script>` строго в этом порядке, без сборщика: первый файл кладёт в
`window.libsodium` рантайм, второй читает его и отдаёт `window.sodium`.

```html
<script src="/vendor/libsodium-sumo.js"></script>
<script src="/vendor/libsodium-wrappers-sumo.js"></script>
```

Дальше — `await sodium.ready` перед первым вызовом.

## Проверка

Целостность npm-архивов на момент вендоринга:

```
libsodium-sumo-0.8.4.tgz           sha512-TMtHShQfVVsaxDygyapvUC3o7YsPgXa/hRWeIgzyFz6w5k/1hirGptCxp1U7XwW3rCskaTTYKgV10v86UiGgNw==
libsodium-wrappers-sumo-0.8.4.tgz  sha512-ql7hcgulKZ3ekfa2DGAogcCKsWU0diA/0nArz1CFzh93WQdb46/Kj18ka/Hifq6uA3Ush34Pc6vU/6HXeRwUkg==
```

SHA-256 самих файлов:

```
a3d0b7eb2c24ba6b5401d88717e65bc6a92786d211bc8b91ed746d24226180f3  libsodium-sumo.js
4e2c5442899321d7d9aea69bef71a3c24a6eab5911b74a5c1ad8bc8c733b0a15  libsodium-wrappers-sumo.js
```

Пересчитать: `shasum -a 256 src/main/resources/static/vendor/*.js`
