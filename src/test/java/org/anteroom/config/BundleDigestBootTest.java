package org.anteroom.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "app.data-dir=build/test-data-bundle")
class BundleDigestBootTest {

    @Autowired
    private BundleDigest bundle;

    @Test
    void coversEverythingServedToTheBrowser() {
        // Нулевой счётчик означал бы, что отпечаток считается по пустоте и не значит
        // ничего, — а выглядел бы он при этом совершенно нормально.
        assertThat(bundle.fileCount())
                .as("бандл не пуст: разметка, модули и вендоренная крипта")
                .isGreaterThan(5);
        assertThat(bundle.digest()).matches("[0-9a-f]{64}");
    }

    @Test
    void staysTheSameOnTheSameBundle() {
        assertThat(bundle.digest()).isEqualTo(bundle.digest());
    }
}
