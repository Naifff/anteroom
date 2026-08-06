package org.anteroom.device;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class DeviceService {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public DeviceService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Запоминает устройство при первом успешном входе.
     *
     * <p><b>Временно.</b> По плану неизвестное устройство без действующего инвайта не должно
     * проходить апгрейд вовсе, но инвайтов до фазы 6 не существует, и жёсткая проверка сейчас
     * не пустила бы вообще никого. До фазы 6 сервер остаётся открытым на регистрацию —
     * личность уже настоящая и подтверждена подписью, но пускают любую. Наружу не выставлять.
     */
    public void rememberSigningKey(String pubkeySign) {
        jdbc.update("INSERT OR IGNORE INTO device (pubkey_sign, pubkey_box, created_at) VALUES (?, '', ?)",
                pubkeySign, clock.millis());
    }

    /**
     * Записывает ключ шифрования устройства.
     *
     * <p>Без X25519 участника обернуть под него ключ комнаты нечем, поэтому клиент присылает
     * его сразу после входа. Ключ публичный — секрета в нём нет.
     */
    public void rememberEncryptionKey(String pubkeySign, String pubkeyBox) {
        jdbc.update("UPDATE device SET pubkey_box = ? WHERE pubkey_sign = ?", pubkeyBox, pubkeySign);
    }

    /**
     * Пущено ли устройство на сервер.
     *
     * <p>Подпись говорит «это тот же ключ», а не «его сюда звали». Пропуском служит
     * погашенное приглашение: либо владение сервером, либо участие хотя бы в одной комнате.
     * Иначе любой, кто открыл адрес, заводил бы на чужом сервере свои комнаты.
     */
    public boolean admitted(String pubkeySign) {
        Integer passes = jdbc.queryForObject(
                "SELECT (SELECT count(*) FROM instance_owner WHERE pubkey_sign = ?)"
                        + " + (SELECT count(*) FROM member WHERE pubkey_sign = ? AND left_at IS NULL)",
                Integer.class, pubkeySign, pubkeySign);
        return passes != null && passes > 0;
    }

    public void grantInstanceOwnership(String pubkeySign) {
        jdbc.update("INSERT OR IGNORE INTO instance_owner (pubkey_sign, granted_at) VALUES (?, ?)",
                pubkeySign, clock.millis());
    }

    /** Ключи шифрования перечисленных устройств. Устройства без него в карту не попадают. */
    public Map<String, String> encryptionKeys(List<String> pubkeySigns) {
        if (pubkeySigns.isEmpty()) {
            return Map.of();
        }
        String places = pubkeySigns.stream().map(key -> "?").collect(Collectors.joining(","));
        return jdbc.query(
                "SELECT pubkey_sign, pubkey_box FROM device WHERE pubkey_sign IN (" + places + ")",
                rs -> {
                    Map<String, String> keys = new java.util.HashMap<>();
                    while (rs.next()) {
                        String box = rs.getString("pubkey_box");
                        if (box != null && !box.isEmpty()) {
                            keys.put(rs.getString("pubkey_sign"), box);
                        }
                    }
                    return keys;
                },
                pubkeySigns.toArray());
    }
}
