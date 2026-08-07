package org.anteroom.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Отпечаток кода, который сервер раздаёт браузерам.
 *
 * <p>Self-hosted-модель снимает главный риск веб-E2E: бандл раздаёт тот же человек, который
 * администрирует сервер. Но проверить, что развёрнуто именно то, что скачано, он должен уметь —
 * иначе публикация хэша релиза не значит ничего.
 *
 * <p>Считается по тому, что реально уходит в браузер, а не по jar целиком: в jar лежат ещё
 * классы, зависимости и миграции, и его хэш меняется от вещей, к раздаваемому коду
 * отношения не имеющих.
 *
 * <p>Печатается в журнал при старте, а не отдаётся эндпоинтом: админ читает свой journald,
 * а лишняя ручка наружу сообщала бы точную версию всякому, кто спросит.
 */
@Component
public class BundleDigest {

    private static final Logger log = LoggerFactory.getLogger(BundleDigest.class);

    private static final String PATTERN = "classpath*:/static/**";
    private static final String ROOT = "/static/";

    private final String digest;
    private final int fileCount;

    public BundleDigest(ResourcePatternResolver resolver) {
        Map<String, byte[]> files = new TreeMap<>();
        try {
            for (Resource resource : resolver.getResources(PATTERN)) {
                if (!resource.isReadable()) {
                    // Каталоги попадают в выборку наравне с файлами и содержимого не имеют.
                    continue;
                }
                files.put(relative(resource), resource.getContentAsByteArray());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("не удалось прочитать бандл", e);
        }

        this.digest = over(files);
        this.fileCount = files.size();
    }

    public String digest() {
        return digest;
    }

    public int fileCount() {
        return fileCount;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void announce() {
        log.info("Отпечаток бандла (файлов: {}): sha256:{}", fileCount, digest);
    }

    /**
     * SHA-256 по путям и содержимому.
     *
     * <p>Порядок обхода classpath не гарантирован, поэтому файлы сортируются: отпечаток,
     * зависящий от порядка, менялся бы от сборки к сборке и не значил бы ничего.
     *
     * <p>Разделитель между путём и содержимым обязателен. Без него «файл {@code ab}
     * с содержимым {@code c}» и «файл {@code a} с содержимым {@code bc}» дают один
     * отпечаток, и подмена проходит незамеченной.
     */
    static String over(Map<String, byte[]> files) {
        MessageDigest digest = sha256();
        for (Map.Entry<String, byte[]> file : new TreeMap<>(files).entrySet()) {
            digest.update(file.getKey().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(file.getValue());
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String relative(Resource resource) throws IOException {
        String path = resource.getURL().toString();
        int at = path.lastIndexOf(ROOT);
        return at < 0 ? path : path.substring(at + ROOT.length());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("в этой JVM нет SHA-256", e);
        }
    }
}
