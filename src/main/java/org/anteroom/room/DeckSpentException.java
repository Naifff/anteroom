package org.anteroom.room;

/**
 * Колода кончилась: роздана 52-я карта.
 *
 * <p>Это не ошибка выдачи и не лимит, который можно поднять, — это конец жизни комнаты.
 * Карты не переиспользуются, чтобы «дама треф» через неделю не оказалась другим человеком,
 * а её старые реплики не остались висеть в ленте выше.
 */
public class DeckSpentException extends RuntimeException {

    public DeckSpentException(String roomId) {
        super("в комнате " + roomId + " роздана вся колода");
    }
}
