package org.anteroom.config;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);

    /** 2 = INCREMENTAL в терминах PRAGMA auto_vacuum. */
    private static final int INCREMENTAL = 2;

    /**
     * Ставит режим уборки до первой миграции.
     *
     * <p>Три причины, почему это не первая строка {@code V1__init.sql} и не
     * {@code connection-init-sql} пула:
     * <ul>
     *   <li>Flyway выполняет миграцию в транзакции, а внутри транзакции SQLite этот PRAGMA
     *       молча игнорирует — получился бы no-op, который выглядит как рабочий код;</li>
     *   <li>драйвер не принимает {@code auto_vacuum} параметром JDBC-URL: неизвестный
     *       параметр он оставляет в имени файла базы;</li>
     *   <li>{@code journal_mode=WAL} применяется при открытии соединения и уже инициализирует
     *       файл, после чего смена режима уборки требует {@code VACUUM}.</li>
     * </ul>
     *
     * <p>{@code VACUUM} здесь одноразовый и на пустой базе мгновенный. На уже наполненной он
     * переписывает файл целиком и блокирует базу — поэтому запускается только если режим ещё
     * не тот, и об этом пишется в лог. Дальше место возвращает
     * {@code PRAGMA incremental_vacuum} порциями, полный VACUUM больше не нужен никогда.
     */
    @Bean
    FlywayMigrationStrategy sqliteAutoVacuumStrategy() {
        return flyway -> {
            DataSource dataSource = flyway.getConfiguration().getDataSource();
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {

                if (currentAutoVacuum(statement) != INCREMENTAL) {
                    log.info("Режим уборки базы переводится в INCREMENTAL, требуется VACUUM");
                    statement.execute("PRAGMA auto_vacuum = INCREMENTAL");
                    statement.execute("VACUUM");

                    int applied = currentAutoVacuum(statement);
                    if (applied != INCREMENTAL) {
                        throw new IllegalStateException(
                                "PRAGMA auto_vacuum остался в режиме " + applied
                                        + ": место после удаления сообщений возвращаться не будет");
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Не удалось задать режим уборки базы", e);
            }
            flyway.migrate();
        };
    }

    private static int currentAutoVacuum(Statement statement) throws SQLException {
        try (ResultSet rs = statement.executeQuery("PRAGMA auto_vacuum")) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }
}
