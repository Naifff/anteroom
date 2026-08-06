package org.anteroom.auth;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

@RestController
public class ChallengeController {

    private final ChallengeService challenges;

    public ChallengeController(ChallengeService challenges) {
        this.challenges = challenges;
    }

    /**
     * Выдаёт вызов на вход. Открыт всем — до подписи сервер не знает, кто спрашивает.
     *
     * <p>Адрес берётся из соединения и нигде не сохраняется: он нужен только счётчику
     * в памяти, чтобы этот эндпоинт нельзя было превратить в дешёвый пожиратель памяти.
     */
    @GetMapping("/api/challenge")
    public ResponseEntity<Challenge> challenge(HttpServletRequest request) {
        Challenge issued = challenges.issue(request.getRemoteAddr());
        return issued == null
                ? ResponseEntity.status(429).build()
                : ResponseEntity.ok(issued);
    }
}
