package org.anteroom;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Интерфейс переводится, а не переписывается на другой язык: английский по умолчанию,
 * русский переключением. Проверяется не «красиво ли переведено» — это не проверить, — а три
 * вещи, каждая из которых молча ломается при правках.
 *
 * <p><b>Почему тестом, а не глазами.</b> Пропущенная строка выглядит как работающий
 * интерфейс: на английской раскладке всплывает одна русская фраза, и заметит её только тот,
 * кто дошёл до этого экрана. У переключателя языка таких экранов десятки.
 */
class LocalizationTest {

    private static final Path STATIC = Path.of("src/main/resources/static");
    private static final Path DICTIONARY = STATIC.resolve("js/i18n.js");

    /** Ключ в словаре: {@code 'some.key': '…'}. */
    private static final Pattern KEY = Pattern.compile("^\\s*'([a-z0-9.\\-]+)':", Pattern.MULTILINE);

    /** Обращение к словарю из разметки и из кода. */
    private static final Pattern USED = Pattern.compile(
            "data-i18n(?:-placeholder|-aria|-title)?=\"([a-z0-9.\\-]+)\"|\\bt\\('([a-z0-9.\\-]+)'");

    private static final Pattern CYRILLIC = Pattern.compile("[А-Яа-яЁё]");

    @Test
    @DisplayName("1. У английского и русского словарей совпадают ключи")
    void dictionariesHaveTheSameKeys() {
        Set<String> english = keysOf(section("en"));
        Set<String> russian = keysOf(section("ru"));

        assertThat(english)
                .as("ключи есть по-английски, но нет по-русски — на русской раскладке пусто")
                .containsExactlyInAnyOrderElementsOf(russian);
        assertThat(english).as("словарь не может быть пустым").isNotEmpty();
    }

    @Test
    @DisplayName("2. Каждый ключ, к которому обращается интерфейс, есть в словаре")
    void everyUsedKeyIsDefined() {
        Set<String> defined = keysOf(section("en"));
        Set<String> missing = new LinkedHashSet<>();

        for (Path file : servedFiles()) {
            Matcher matcher = USED.matcher(read(file));
            while (matcher.find()) {
                String key = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
                if (!defined.contains(key)) {
                    missing.add(file.getFileName() + ": " + key);
                }
            }
        }

        assertThat(missing)
                .as("обращение к несуществующему ключу выводит на экран сам ключ")
                .isEmpty();
    }

    @Test
    @DisplayName("3. Вне словаря в раздаваемом коде русского текста не осталось")
    void noRussianOutsideTheDictionary() {
        Set<String> leftovers = new LinkedHashSet<>();

        for (Path file : servedFiles()) {
            for (String line : withoutComments(read(file)).split("\n")) {
                if (CYRILLIC.matcher(line).find()) {
                    leftovers.add(file.getFileName() + ": " + line.trim());
                }
            }
        }

        assertThat(leftovers)
                .as("зашитая мимо словаря строка не переключается вместе с языком")
                .isEmpty();
    }

    /**
     * Комментарии остаются русскими намеренно: они объясняют решения тому, кто правит код,
     * и к интерфейсу отношения не имеют. Вырезаются грубо — по началу строки, — потому что
     * задача теста найти строку на экране, а не разобрать JavaScript.
     */
    private static String withoutComments(String source) {
        return source.replaceAll("(?s)<!--.*?-->", "")
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)^\\s*//.*$", "")
                .replaceAll("(?m)\\s+//\\s+[^'\"`]*$", "");
    }

    private static Set<String> keysOf(String section) {
        Set<String> keys = new LinkedHashSet<>();
        Matcher matcher = KEY.matcher(section);
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
        return keys;
    }

    /** Тело одного языка внутри словаря: от {@code <язык>: {} до строки, закрывающей блок. */
    private static String section(String language) {
        String source = read(DICTIONARY);
        int start = source.indexOf("\n  " + language + ": {");
        assertThat(start).as("в словаре нет раздела " + language).isNotNegative();
        int end = source.indexOf("\n  },", start);
        return source.substring(start, end < 0 ? source.length() : end);
    }

    /** Всё, что уходит в браузер, кроме вендоренного стороннего кода. */
    private static Iterable<Path> servedFiles() {
        try (Stream<Path> tree = Files.walk(STATIC)) {
            return tree.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".html") || path.toString().endsWith(".js"))
                    .filter(path -> !path.toString().contains("vendor"))
                    .filter(path -> !path.equals(DICTIONARY))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
