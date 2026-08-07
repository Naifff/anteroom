package org.anteroom.file;

/** Тело оказалось больше того, что разрешено и что было объявлено при выдаче токена. */
public class FileTooLargeException extends RuntimeException {

    public FileTooLargeException(String message) {
        super(message);
    }
}
