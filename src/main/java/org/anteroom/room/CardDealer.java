package org.anteroom.room;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Раздача карт.
 *
 * <p>Участник получает карту, а не выбранный ник: имя выводится из ключа, выбрать его
 * нельзя, значит и подделать нельзя. В анонимной комнате свободный ник — готовый вектор
 * выдачи себя за другого.
 *
 * <p>Ключ HMAC — номер комнаты. Без этой привязки один человек во всех комнатах получал бы
 * одну и ту же карту, и участники разных комнат сопоставили бы, что это одно лицо.
 *
 * <p>Уникальность здесь не правило, а свойство конструкции: в колоде не бывает двух дам
 * треф, поэтому счётчики, словарь форм прилагательного и прочее разрешение коллизий
 * не нужны вовсе.
 */
public final class CardDealer {

    public static final int DECK_SIZE = 52;

    private static final String ALGORITHM = "HmacSHA256";

    private CardDealer() {
    }

    /**
     * Карта устройства в комнате.
     *
     * @param taken занятые карты, включая карты вышедших: выбывшая карта в колоду
     *              не возвращается
     * @throws DeckSpentException когда роздана вся колода
     */
    public static int deal(String roomId, String pubkeySign, Set<Integer> taken) {
        if (taken.size() >= DECK_SIZE) {
            throw new DeckSpentException(roomId);
        }

        int card = position(roomId, pubkeySign);
        // Занятая карта сдвигает на следующую свободную по кругу. Выход за 52 невозможен:
        // выше уже проверено, что свободная есть хотя бы одна.
        while (taken.contains(card)) {
            card = (card + 1) % DECK_SIZE;
        }
        return card;
    }

    private static int position(String roomId, String pubkeySign) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(roomId.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] digest = mac.doFinal(pubkeySign.getBytes(StandardCharsets.UTF_8));

            // Четыре байта без знака: одного байта хватило бы по диапазону, но 256 не делится
            // на 52 нацело, и младшие карты выпадали бы заметно чаще.
            long value = ((long) (digest[0] & 0xFF) << 24)
                    | ((digest[1] & 0xFF) << 16)
                    | ((digest[2] & 0xFF) << 8)
                    | (digest[3] & 0xFF);
            return (int) (value % DECK_SIZE);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("в этой JVM нет " + ALGORITHM, e);
        }
    }
}
