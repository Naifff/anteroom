package org.anteroom.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

/**
 * Отпечаток раздаваемого браузеру бандла.
 *
 * <p>Обещание из модели угроз — «хэш бандла публикуется, чтобы админ мог сверить, что
 * развернул именно то, что скачал» — стоит ровно столько, сколько стоит сам отпечаток.
 * Поэтому он считается не по «примерно тем же файлам», а по всему, что уходит в браузер.
 */
class BundleDigestTest {

    @Test
    void doesNotDependOnTheOrderFilesWereFound() {
        // Порядок обхода классpath не гарантирован. Отпечаток, зависящий от него,
        // менялся бы от сборки к сборке и не значил бы ничего.
        Map<String, byte[]> straight = new LinkedHashMap<>();
        straight.put("index.html", "разметка".getBytes());
        straight.put("js/identity.js", "код".getBytes());

        Map<String, byte[]> reversed = new LinkedHashMap<>();
        reversed.put("js/identity.js", "код".getBytes());
        reversed.put("index.html", "разметка".getBytes());

        assertThat(BundleDigest.over(straight)).isEqualTo(BundleDigest.over(reversed));
    }

    @Test
    void changesWhenAFileChanges() {
        Map<String, byte[]> before = new TreeMap<>(Map.of("js/identity.js", "код".getBytes()));
        Map<String, byte[]> after = new TreeMap<>(Map.of("js/identity.js", "другой код".getBytes()));

        assertThat(BundleDigest.over(before)).isNotEqualTo(BundleDigest.over(after));
    }

    @Test
    void changesWhenAFileIsRenamed() {
        Map<String, byte[]> here = new TreeMap<>(Map.of("js/identity.js", "код".getBytes()));
        Map<String, byte[]> there = new TreeMap<>(Map.of("js/подмена.js", "код".getBytes()));

        assertThat(BundleDigest.over(here)).isNotEqualTo(BundleDigest.over(there));
    }

    @Test
    void changesWhenAFileIsAdded() {
        Map<String, byte[]> lean = new TreeMap<>(Map.of("index.html", "разметка".getBytes()));
        Map<String, byte[]> extra = new TreeMap<>(Map.of(
                "index.html", "разметка".getBytes(), "js/лишний.js", new byte[0]));

        assertThat(BundleDigest.over(lean)).isNotEqualTo(BundleDigest.over(extra));
    }

    @Test
    void cannotBeFooledByMovingBytesAcrossTheBoundary() {
        // Без разделителей «файл ab с содержимым c» и «файл a с содержимым bc» дали бы
        // один отпечаток, и подмена прошла бы незамеченной.
        Map<String, byte[]> first = new TreeMap<>(Map.of("ab", "c".getBytes()));
        Map<String, byte[]> second = new TreeMap<>(Map.of("a", "bc".getBytes()));

        assertThat(BundleDigest.over(first)).isNotEqualTo(BundleDigest.over(second));
    }

    @Test
    void readsAsHex() {
        assertThat(BundleDigest.over(Map.of("index.html", "разметка".getBytes())))
                .matches("[0-9a-f]{64}");
    }
}
