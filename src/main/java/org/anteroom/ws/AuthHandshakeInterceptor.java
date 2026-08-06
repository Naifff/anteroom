package org.anteroom.ws;

import java.util.Map;

import org.anteroom.auth.ChallengeService;
import org.anteroom.device.DeviceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Пускает в сокет только устройство, подписавшее выданный вызов.
 *
 * <p>Вызов и подпись едут параметрами запроса, а не во фрагменте: секрета в них нет,
 * оба одноразовые и публичные. Приватный ключ браузер не отдаёт никогда.
 *
 * <p>Отказ всегда один и тот же — 401 без подробностей. Отличать «нет такого вызова» от
 * «подпись не сошлась» снаружи незачем, а подсказывать перебирающему тем более.
 */
@Component
public class AuthHandshakeInterceptor implements HandshakeInterceptor {

    public static final String DEVICE_ATTRIBUTE = "pubkey_sign";

    /**
     * Адрес соединения — только для счётчиков в памяти. На диск он не попадает и в базе
     * его нет: журнал соединений в этой системе не предусмотрен.
     */
    public static final String ADDRESS_ATTRIBUTE = "address";

    private static final Logger log = LoggerFactory.getLogger(AuthHandshakeInterceptor.class);

    private final ChallengeService challenges;
    private final DeviceService devices;

    public AuthHandshakeInterceptor(ChallengeService challenges, DeviceService devices) {
        this.challenges = challenges;
        this.devices = devices;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler handler, Map<String, Object> attributes) {
        var query = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams();
        String nonce = query.getFirst("nonce");
        String device = query.getFirst("device");
        String signature = query.getFirst("signature");

        if (!challenges.redeem(nonce, device, signature)) {
            // Без тела и без причины: логировать тут тоже нечего, кроме факта отказа.
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            log.debug("Апгрейд отклонён: вызов не погашен");
            return false;
        }

        devices.rememberSigningKey(device);
        attributes.put(DEVICE_ATTRIBUTE, device);
        var remote = request.getRemoteAddress();
        attributes.put(ADDRESS_ATTRIBUTE, remote == null ? "" : remote.getAddress().getHostAddress());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler handler, Exception exception) {
    }
}
