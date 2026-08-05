package org.anteroom.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Проверка подписи сверяется с векторами RFC 8032, а не с собственной реализацией:
 * сойтись сама с собой она может и будучи неверной.
 */
class Ed25519KeysTest {

    private static final HexFormat HEX = HexFormat.of();

    private record Vector(String secret, String publicKey, String message, String signature) {
    }

    private static final List<Vector> RFC_8032 = List.of(
            new Vector("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60",
                    "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
                    "",
                    "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"),
            new Vector("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb",
                    "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c",
                    "72",
                    "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"),
            new Vector("c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7",
                    "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025",
                    "af82",
                    "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a"));

    @Test
    void acceptsSignaturesFromRfcVectors() {
        for (Vector vector : RFC_8032) {
            assertThat(Ed25519Keys.verify(
                    HEX.parseHex(vector.signature()),
                    HEX.parseHex(vector.message()),
                    HEX.parseHex(vector.publicKey())))
                    .as("вектор %s", vector.publicKey().substring(0, 8))
                    .isTrue();
        }
    }

    @Test
    void rejectsTamperedSignature() {
        Vector vector = RFC_8032.get(1);
        byte[] signature = HEX.parseHex(vector.signature());
        signature[0] ^= 1;

        assertThat(Ed25519Keys.verify(signature, HEX.parseHex(vector.message()), HEX.parseHex(vector.publicKey())))
                .isFalse();
    }

    @Test
    void rejectsSignatureOfAnotherMessage() {
        Vector vector = RFC_8032.get(1);

        assertThat(Ed25519Keys.verify(
                HEX.parseHex(vector.signature()),
                HEX.parseHex("73"),
                HEX.parseHex(vector.publicKey())))
                .isFalse();
    }

    @Test
    void rejectsSignatureUnderAnotherKey() {
        Vector signed = RFC_8032.get(1);
        Vector stranger = RFC_8032.get(2);

        assertThat(Ed25519Keys.verify(
                HEX.parseHex(signed.signature()),
                HEX.parseHex(signed.message()),
                HEX.parseHex(stranger.publicKey())))
                .isFalse();
    }

    @Test
    void rejectsPublicKeyOfWrongLength() {
        // Ключ приходит из сети. Короткий или длинный — отказ, а не исключение наружу.
        assertThat(Ed25519Keys.verify(new byte[64], new byte[] { 1 }, new byte[31])).isFalse();
    }

    @Test
    void rejectsGarbageInsteadOfPublicKey() {
        assertThat(Ed25519Keys.verify(new byte[64], new byte[] { 1 }, new byte[32])).isFalse();
    }

    @Test
    void keepsPublicKeyThroughRawEncoding() {
        // Публичная часть уезжает клиенту 32 байтами и возвращается ими же, поэтому
        // обе стороны преобразования обязаны сходиться.
        KeyPair pair = Ed25519Keys.newKeyPair();
        byte[] raw = Ed25519Keys.rawPublicKey(pair.getPublic());

        assertThat(raw).hasSize(32);
        assertThat(Ed25519Keys.rawPublicKey(Ed25519Keys.publicKey(raw))).isEqualTo(raw);
    }

    @Test
    void verifiesItsOwnSignature() {
        KeyPair pair = Ed25519Keys.newKeyPair();
        byte[] message = "проверка".getBytes();
        byte[] signature = Ed25519Keys.sign(message, pair.getPrivate());

        assertThat(Ed25519Keys.verify(signature, message, Ed25519Keys.rawPublicKey(pair.getPublic()))).isTrue();
    }

    @Test
    void reproducesRfcPublicKeyFromRawBytes() {
        // Разбор сырого ключа — место, где легко перепутать порядок байт: он little-endian,
        // а старший бит последнего байта это знак x, а не часть числа.
        byte[] raw = HEX.parseHex(RFC_8032.get(0).publicKey());

        assertThat(Ed25519Keys.rawPublicKey(Ed25519Keys.publicKey(raw))).isEqualTo(raw);
    }

    @Test
    void refusesToBuildKeyFromWrongLength() {
        assertThatThrownBy(() -> Ed25519Keys.publicKey(new byte[31]))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
