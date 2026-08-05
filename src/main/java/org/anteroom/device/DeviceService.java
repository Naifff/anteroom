package org.anteroom.device;

import java.time.Clock;

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
     *
     * <p>Ключ шифрования (X25519) здесь не заполняется: он нужен с фазы 5, когда появятся
     * обёртки ключа комнаты, и приедет отдельным сообщением по сокету.
     */
    public void rememberSigningKey(String pubkeySign) {
        jdbc.update("INSERT OR IGNORE INTO device (pubkey_sign, pubkey_box, created_at) VALUES (?, '', ?)",
                pubkeySign, clock.millis());
    }
}
