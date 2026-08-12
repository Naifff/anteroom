#!/usr/bin/env bash
#
# Проверка, которую может выполнить посторонний.
#
# Отвечает на два вопроса, ради которых в этом проекте вообще заведена сборка:
# проходят ли тесты и правда ли, что два прогона на одних исходниках дают
# побайтово одинаковый jar. Второе — не украшение: на воспроизводимости держится
# смысл публиковать контрольную сумму, потому что иначе по ней нечего проверить,
# кроме целости скачанного файла.
#
# Скрипт не привязан ни к какому CI: то же самое выполняется на машине человека,
# который решает, доверять ли раздаваемому коду. Workflow в .github его просто
# вызывает.
#
# Требуется JDK 21. Версия компиляции пришпилена toolchain'ом, так что JAVA_HOME
# на результат не влияет — но сам Gradle без JDK не запустится.

set -euo pipefail

cd "$(dirname "$0")"

# sha256sum есть в Linux, shasum — в macOS. Считаем одинаково, различается только имя.
if command -v sha256sum >/dev/null 2>&1; then
    hash_of() { sha256sum "$1" | cut -d' ' -f1; }
elif command -v shasum >/dev/null 2>&1; then
    hash_of() { shasum -a 256 "$1" | cut -d' ' -f1; }
else
    echo "verify: neither sha256sum nor shasum found" >&2
    exit 2
fi

JAR=build/libs/messenger.jar
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Кэш сборки выключен намеренно. С ним вторая сборка вправе не собирать ничего,
# а достать готовый артефакт по совпадению входов — и сравнение покажет, что
# работает кэш, а не что сборка детерминирована. Проверяем именно второе.
GRADLE=(./gradlew --quiet --console=plain --no-build-cache)

echo "==> Tests"
"${GRADLE[@]}" clean check

echo "==> Build 1 of 2"
"${GRADLE[@]}" clean bootJar
cp "$JAR" "$WORK/first.jar"
FIRST="$(hash_of "$WORK/first.jar")"

# Именно clean, а не повторный bootJar: без него Gradle посчитает задачу
# актуальной, ничего не соберёт и сравнение выродится в сверку файла с самим собой.
echo "==> Build 2 of 2"
"${GRADLE[@]}" clean bootJar
cp "$JAR" "$WORK/second.jar"
SECOND="$(hash_of "$WORK/second.jar")"

echo
if [ "$FIRST" = "$SECOND" ]; then
    echo "Tests:        passed"
    echo "Reproducible: yes"
    echo "sha256:       $FIRST  messenger.jar"
    echo
    echo "The published checksum can be compared against this value."
else
    echo "Tests:        passed"
    echo "Reproducible: NO — two builds of the same sources differ" >&2
    echo "  build 1: $FIRST" >&2
    echo "  build 2: $SECOND" >&2
    echo >&2
    echo "Something in the jar depends on build time or filesystem order." >&2
    echo "See bootJar { preserveFileTimestamps, reproducibleFileOrder } in build.gradle." >&2
    exit 1
fi
