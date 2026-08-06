package org.anteroom.auth;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

public class ServerKeyFailureAnalyzer extends AbstractFailureAnalyzer<InsecureServerKeyException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, InsecureServerKeyException cause) {
        return new FailureAnalysis(
                "Ключ сервера " + cause.file() + " доступен не только владельцу: права "
                        + cause.permissions() + ".\n"
                        + "Подменивший этот файл сможет выдавать себя за сервер при входе, "
                        + "поэтому запуск остановлен.",
                "chmod 600 " + cause.file() + "\nchown <пользователь сервиса> " + cause.file(),
                cause);
    }
}
