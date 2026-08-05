package org.anteroom.auth;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.EdECPublicKey;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;

/**
 * Ed25519 на штатном JDK: с 15-й версии он есть в java.security, стороннего крипто
 * на сервер тащить незачем.
 *
 * <p>Ключи ходят по проводу сырыми 32 байтами, как их отдаёт libsodium в браузере,
 * а Java хочет точку кривой. Всё преобразование — здесь, в одном месте.
 */
public final class Ed25519Keys {

    private static final int RAW_LENGTH = 32;
    private static final String ALGORITHM = "Ed25519";

    private Ed25519Keys() {
    }

    /**
     * Подпись под сообщение.
     *
     * <p>Ключ и подпись приходят из сети, поэтому любая кривизна — это {@code false},
     * а не исключение наружу: отличать «неверная подпись» от «неразобранный ключ»
     * снаружи нельзя, да и незачем.
     */
    public static boolean verify(byte[] signature, byte[] message, byte[] rawPublicKey) {
        try {
            Signature verifier = Signature.getInstance(ALGORITHM);
            verifier.initVerify(publicKey(rawPublicKey));
            verifier.update(message);
            return verifier.verify(signature);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Сырые 32 байта в ключ.
     *
     * <p>Байты little-endian, а старший бит последнего — знак координаты x, а не часть
     * числа. Забыть его снять означает получить чужую точку и молча отвергать верные подписи.
     */
    public static PublicKey publicKey(byte[] raw) {
        if (raw == null || raw.length != RAW_LENGTH) {
            throw new IllegalArgumentException("публичный ключ Ed25519 должен быть 32 байта");
        }

        byte[] reversed = new byte[RAW_LENGTH];
        for (int i = 0; i < RAW_LENGTH; i++) {
            reversed[i] = raw[RAW_LENGTH - 1 - i];
        }
        boolean xOdd = (reversed[0] & 0x80) != 0;
        reversed[0] &= (byte) 0x7F;

        try {
            EdECPublicKeySpec spec = new EdECPublicKeySpec(
                    NamedParameterSpec.ED25519, new EdECPoint(xOdd, new BigInteger(1, reversed)));
            return KeyFactory.getInstance(ALGORITHM).generatePublic(spec);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("не разбирается как ключ Ed25519", e);
        }
    }

    public static byte[] rawPublicKey(PublicKey key) {
        EdECPoint point = ((EdECPublicKey) key).getPoint();

        byte[] raw = new byte[RAW_LENGTH];
        byte[] y = point.getY().toByteArray();
        // toByteArray отдаёт big-endian переменной длины: старшие нули срезаны, а лишний
        // ведущий ноль от знака, наоборот, может появиться. Копируем от младшего конца.
        for (int i = 0; i < y.length && i < RAW_LENGTH; i++) {
            raw[i] = y[y.length - 1 - i];
        }
        if (point.isXOdd()) {
            raw[RAW_LENGTH - 1] |= (byte) 0x80;
        }
        return raw;
    }

    public static byte[] sign(byte[] message, PrivateKey key) {
        try {
            Signature signer = Signature.getInstance(ALGORITHM);
            signer.initSign(key);
            signer.update(message);
            return signer.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("не удалось подписать", e);
        }
    }

    public static KeyPair newKeyPair() {
        try {
            return KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("в этой JVM нет Ed25519", e);
        }
    }
}
