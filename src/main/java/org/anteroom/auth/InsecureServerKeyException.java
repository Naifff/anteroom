package org.anteroom.auth;

import java.nio.file.Path;

/**
 * Права на ключ сервера позволяют читать его кому-то ещё.
 *
 * <p>Отдельный тип, чтобы Spring Boot напечатал внятный отказ вместо стены
 * {@code UnsatisfiedDependencyException}: администратор должен увидеть причину и команду
 * починки, а не разбирать стектрейс.
 */
public class InsecureServerKeyException extends RuntimeException {

    private final transient Path file;
    private final String permissions;

    public InsecureServerKeyException(Path file, String permissions) {
        super(file + ": слишком открытые права " + permissions + ", нужно rw-------");
        this.file = file;
        this.permissions = permissions;
    }

    public Path file() {
        return file;
    }

    public String permissions() {
        return permissions;
    }
}
