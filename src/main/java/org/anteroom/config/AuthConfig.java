package org.anteroom.config;

import java.nio.file.Path;

import org.anteroom.auth.ServerKeyStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AuthConfig {

    @Bean
    ServerKeyStore serverKeyStore(@Value("${app.data-dir:./data}") String dataDir) {
        return new ServerKeyStore(Path.of(dataDir));
    }
}
