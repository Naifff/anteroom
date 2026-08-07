package org.anteroom.message;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Одноразовая ссылка.
 *
 * <p><b>Открытие не по GET.</b> Превью-боты мессенджеров и корпоративные антивирусы ходят
 * по ссылке раньше человека: по GET они сожгли бы записку до того, как её кто-то увидел.
 * Поэтому GET отдаёт страницу с предупреждением, а тратит ссылку только явное нажатие.
 */
@Controller
public class OnceController {

    private final OneTimeService links;

    public OnceController(OneTimeService links) {
        this.links = links;
    }

    /**
     * Промежуточная страница. Отдельная от SPA, а не маршрут внутри неё: открывающий
     * может быть посторонним, и заводить ему личность устройства ради одной записки незачем.
     *
     * <p>Токен и ключ приезжают во фрагменте, то есть сюда не попадают вовсе.
     */
    @GetMapping("/once")
    public String page() {
        return "forward:/once.html";
    }

    /**
     * Сжигание.
     *
     * <p>Токен едет телом запроса, не путём: путь попадает в {@code access.log} веб-сервера,
     * и одноразовость ссылки этого не отменяет — журнал переживёт саму записку.
     */
    @PostMapping(value = "/api/once", consumes = MediaType.TEXT_PLAIN_VALUE)
    @ResponseBody
    public ResponseEntity<byte[]> open(@RequestBody(required = false) String token) {
        byte[] ciphertext = links.burn(token);
        if (ciphertext == null) {
            // Уже открытая, протухшая и никогда не существовавшая отвечают одинаково.
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                // Записки в кэше браузера делать нечего: она уничтожена на сервере, но
                // кэш об этом не знает и отдаст её ещё раз.
                .header("Cache-Control", "no-store")
                .body(ciphertext);
    }
}
