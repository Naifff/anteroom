package org.anteroom.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Создаёт каталог данных до того, как поднимется DataSource.
 *
 * <p>Обычного {@code @Bean} тут мало: SQLite открывает файл при инициализации пула, и если
 * каталога нет, старт падает раньше, чем успел бы отработать любой бин. Поэтому слушатель
 * на {@link ApplicationEnvironmentPreparedEvent} — самая ранняя точка, где уже виден
 * {@code app.data-dir} из командной строки и application.yml.
 *
 * <p>Регистрируется через {@code META-INF/spring.factories}, а не аннотацией: к моменту
 * события контекста ещё нет.
 */
public class DataDirectoryInitializer implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    public static final String PROPERTY = "app.data-dir";
    public static final String DEFAULT = "./data";

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        ConfigurableEnvironment environment = event.getEnvironment();
        Path dataDir = Paths.get(environment.getProperty(PROPERTY, DEFAULT)).toAbsolutePath().normalize();
        try {
            Files.createDirectories(dataDir);
            Files.createDirectories(dataDir.resolve("blobs"));
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось создать каталог данных " + dataDir, e);
        }
    }
}
