package org.anteroom.invite;

/**
 * Ответ на попытку погасить инвайт.
 *
 * <p>Отказ всегда один и тот же, без подробностей: по разнице между «нет такого»,
 * «протух» и «исчерпан» видно, какой инвайт существовал.
 */
public record Redemption(boolean accepted, String reason, String roomId, String role, byte[] wrappedKey) {

    public static final String REFUSED = "приглашение недействительно";

    public static Redemption refused() {
        return new Redemption(false, REFUSED, null, null, null);
    }

    public static Redemption accepted(String roomId, String role, byte[] wrappedKey) {
        return new Redemption(true, null, roomId, role, wrappedKey);
    }
}
