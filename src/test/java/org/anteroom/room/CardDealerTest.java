package org.anteroom.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

class CardDealerTest {

    private static final String ROOM = "room-a";
    private static final String DEVICE = "wLxJx3nQ8Qb2f0mHhK7d1aZpQe4yVtRu6sN9cB2gE0k";

    private static Set<Integer> taken(int... cards) {
        Set<Integer> set = new HashSet<>();
        for (int card : cards) {
            set.add(card);
        }
        return set;
    }

    @Test
    void dealsSameCardToSameDeviceInSameRoom() {
        // Карта выводится из ключа, а не назначается по счётчику: восстановленное из слов
        // устройство обязано получить ту же карту, иначе его старые реплики осиротеют.
        int first = CardDealer.deal(ROOM, DEVICE, Set.of());

        assertThat(CardDealer.deal(ROOM, DEVICE, Set.of())).isEqualTo(first);
    }

    @Test
    void dealsCardWithinDeck() {
        for (int i = 0; i < 200; i++) {
            assertThat(CardDealer.deal("room-" + i, DEVICE, Set.of()))
                    .isBetween(0, CardDealer.DECK_SIZE - 1);
        }
    }

    @Test
    void bindsCardToRoom() {
        // Без привязки к комнате один человек во всех комнатах — одна и та же карта,
        // и участники разных комнат сопоставляют, что это одно лицо.
        Set<Integer> across = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            across.add(CardDealer.deal("room-" + i, DEVICE, Set.of()));
        }

        assertThat(across).hasSizeGreaterThan(1);
    }

    @Test
    void movesToNextCardWhenItsOwnIsTaken() {
        int natural = CardDealer.deal(ROOM, DEVICE, Set.of());

        assertThat(CardDealer.deal(ROOM, DEVICE, taken(natural)))
                .isEqualTo((natural + 1) % CardDealer.DECK_SIZE);
    }

    @Test
    void skipsRunOfTakenCards() {
        int natural = CardDealer.deal(ROOM, DEVICE, Set.of());
        Set<Integer> busy = taken(natural,
                (natural + 1) % CardDealer.DECK_SIZE,
                (natural + 2) % CardDealer.DECK_SIZE);

        assertThat(CardDealer.deal(ROOM, DEVICE, busy))
                .isEqualTo((natural + 3) % CardDealer.DECK_SIZE);
    }

    @Test
    void wrapsAroundEndOfDeck() {
        Set<Integer> busy = new HashSet<>();
        IntStream.range(0, CardDealer.DECK_SIZE).forEach(busy::add);
        int natural = CardDealer.deal(ROOM, DEVICE, Set.of());
        busy.remove(0);
        busy.remove(natural);

        // Свободны только 0 и своя карта — своя и достаётся.
        assertThat(CardDealer.deal(ROOM, DEVICE, busy)).isEqualTo(natural);
    }

    @Test
    void takesTheOnlyFreeCardEvenFarFromItsOwn() {
        Set<Integer> busy = new HashSet<>();
        IntStream.range(0, CardDealer.DECK_SIZE).forEach(busy::add);
        int natural = CardDealer.deal(ROOM, DEVICE, Set.of());
        int free = (natural + 40) % CardDealer.DECK_SIZE;
        busy.remove(free);

        assertThat(CardDealer.deal(ROOM, DEVICE, busy)).isEqualTo(free);
    }

    @Test
    void refusesWhenDeckIsSpent() {
        // Не ошибка выдачи, а конец жизни комнаты: карты не переиспользуются, чтобы
        // «дама треф» через неделю не оказалась другим человеком.
        Set<Integer> busy = new HashSet<>();
        IntStream.range(0, CardDealer.DECK_SIZE).forEach(busy::add);

        assertThatThrownBy(() -> CardDealer.deal(ROOM, DEVICE, busy))
                .isInstanceOf(DeckSpentException.class);
    }

    @Test
    void spreadsDevicesAcrossDeck() {
        // Не про равномерность ради красоты: если бы вывод давал одну и ту же карту всем,
        // разрешение коллизий превратило бы раздачу в порядковый счётчик.
        Set<Integer> cards = new HashSet<>();
        for (int i = 0; i < 300; i++) {
            cards.add(CardDealer.deal(ROOM, "device-" + i, Set.of()));
        }

        assertThat(cards).hasSizeGreaterThan(40);
    }
}
