package org.anteroom.file;

/**
 * Токен не тот, протух или уже потрачен.
 *
 * <p>Все три причины наружу выглядят одинаково: различать их — значит подсказывать
 * перебирающему, насколько он близко.
 */
public class UploadRefusedException extends RuntimeException {

    public UploadRefusedException(String message) {
        super(message);
    }
}
