package org.anteroom.invite;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class JoinPageController {

    /**
     * Ссылка-приглашение ведёт на {@code /join#<токен>.<эфемерный ключ>}, а отдаётся тот же
     * SPA. Фрагмент сюда не приезжает и приехать не может — его разбирает браузер.
     */
    @GetMapping("/join")
    public String join() {
        return "forward:/index.html";
    }
}
