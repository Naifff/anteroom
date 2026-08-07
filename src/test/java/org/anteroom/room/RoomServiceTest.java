package org.anteroom.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

import org.anteroom.device.DeviceService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = "app.data-dir=build/test-data-room")
class RoomServiceTest {

    private static final Path DATA_DIR = Path.of("build/test-data-room");

    @Autowired
    private RoomService rooms;

    @Autowired
    private DeviceService devices;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeAll
    static void startFromEmptyDirectory() throws IOException {
        if (!Files.exists(DATA_DIR)) {
            return;
        }
        try (var paths = Files.walk(DATA_DIR)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    @BeforeEach
    void clear() {
        jdbc.update("DELETE FROM message");
        jdbc.update("DELETE FROM member_key");
        jdbc.update("DELETE FROM member");
        jdbc.update("DELETE FROM room");
        jdbc.update("DELETE FROM device");
    }

    private String device(String name) {
        devices.rememberSigningKey(name);
        return name;
    }

    @Test
    void seatsCreatorAsOwner() {
        String owner = device("owner-1");
        String roomId = rooms.create(owner, 3600, 86400, true);

        assertThat(rooms.role(roomId, owner)).isEqualTo("owner");
        assertThat(rooms.card(roomId, owner)).isBetween(0, 51);
    }

    @Test
    void clampsCeilingToFiveDays() {
        // Потолок держится низким сознательно: исключённый участник уносит читаемую историю
        // за этот срок, и на полутора месяцах исключение стало бы формальностью.
        String roomId = rooms.create(device("owner-2"), 3600, 999_999_999, true);

        assertThat(rooms.find(roomId).maxTtl()).isEqualTo(RoomService.TTL_CEILING);
    }

    @Test
    void clampsDefaultToRoomCeiling() {
        String roomId = rooms.create(device("owner-3"), 999_999, 3600, true);

        assertThat(rooms.find(roomId).defaultTtl()).isEqualTo(3600);
    }

    @Test
    void refusesTtlBelowFloor() {
        assertThatThrownBy(() -> rooms.create(device("owner-4"), 1, 1, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void startsAtFirstEpoch() {
        String roomId = rooms.create(device("owner-5"), 3600, 86400, true);

        assertThat(rooms.find(roomId).keyEpoch()).isEqualTo(1);
    }

    @Test
    void givesEveryMemberOwnCard() {
        String roomId = rooms.create(device("owner-6"), 3600, 86400, true);

        Set<Integer> cards = new HashSet<>();
        cards.add(rooms.card(roomId, "owner-6"));
        for (int i = 0; i < 20; i++) {
            cards.add(rooms.join(roomId, device("member-" + i), "member", "owner-6"));
        }

        assertThat(cards).hasSize(21);
    }

    @Test
    void keepsCardOfDeviceThatJoinsTwice() {
        // Реконнект и повторный вход — не новый участник. Иначе колода тратилась бы
        // на переподключения.
        String roomId = rooms.create(device("owner-7"), 3600, 86400, true);
        int first = rooms.join(roomId, device("member-a"), "member", "owner-7");

        assertThat(rooms.join(roomId, "member-a", "member", "owner-7")).isEqualTo(first);
        assertThat(rooms.find(roomId).seatsTaken()).isEqualTo(2);
    }

    @Test
    void doesNotReturnCardOfDepartedMember() {
        // Иначе «восьмёрка бубён» через неделю окажется другим человеком, а её старые
        // реплики останутся висеть в ленте выше.
        String roomId = rooms.create(device("owner-8"), 3600, 86400, true);
        int left = rooms.join(roomId, device("member-b"), "member", "owner-8");
        rooms.remove(roomId, "member-b");

        for (int i = 0; i < 30; i++) {
            assertThat(rooms.join(roomId, device("late-" + i), "member", "owner-8")).isNotEqualTo(left);
        }
    }

    @Test
    void keepsDepartedMemberRowWithLeftAt() {
        String roomId = rooms.create(device("owner-9"), 3600, 86400, true);
        rooms.join(roomId, device("member-c"), "member", "owner-9");
        rooms.remove(roomId, "member-c");

        assertThat(jdbc.queryForObject(
                "SELECT left_at FROM member WHERE room_id = ? AND pubkey_sign = ?",
                Long.class, roomId, "member-c")).isNotNull();
    }

    @Test
    void stopsAcceptingWhenDeckIsSpent() {
        String roomId = rooms.create(device("owner-10"), 3600, 86400, true);
        for (int i = 0; i < CardDealer.DECK_SIZE - 1; i++) {
            rooms.join(roomId, device("crowd-" + i), "member", "owner-10");
        }

        assertThat(rooms.find(roomId).seatsTaken()).isEqualTo(CardDealer.DECK_SIZE);
        assertThatThrownBy(() -> rooms.join(roomId, device("odd-one-out"), "member", "owner-10"))
                .isInstanceOf(DeckSpentException.class);
    }

    @Test
    void countsSeatsOverWholeLifeOfRoom() {
        String roomId = rooms.create(device("owner-11"), 3600, 86400, true);
        rooms.join(roomId, device("member-d"), "member", "owner-11");
        rooms.remove(roomId, "member-d");

        // Ушедший место не освобождает: счётчик считает розданное за всё время, а не
        // присутствующих сейчас.
        assertThat(rooms.find(roomId).seatsTaken()).isEqualTo(2);
    }

    @Test
    void listsOnlyPresentMembers() {
        String roomId = rooms.create(device("owner-12"), 3600, 86400, true);
        rooms.join(roomId, device("member-e"), "member", "owner-12");
        rooms.join(roomId, device("member-f"), "member", "owner-12");
        rooms.remove(roomId, "member-e");

        assertThat(rooms.members(roomId)).extracting(Member::pubkeySign)
                .containsExactlyInAnyOrder("owner-12", "member-f");
    }

    @Test
    void doesNotLeakMembersBetweenRooms() {
        String first = rooms.create(device("owner-13"), 3600, 86400, true);
        String second = rooms.create(device("owner-14"), 3600, 86400, true);

        assertThat(rooms.members(first)).extracting(Member::pubkeySign).containsExactly("owner-13");
        assertThat(rooms.members(second)).extracting(Member::pubkeySign).containsExactly("owner-14");
    }

    @Test
    void givesSameDeviceDifferentCardsInDifferentRooms() {
        String traveller = device("traveller");
        Set<Integer> cards = new HashSet<>();
        for (int i = 0; i < 40; i++) {
            String roomId = rooms.create(device("host-" + i), 3600, 86400, true);
            cards.add(rooms.join(roomId, traveller, "member", "host-" + i));
        }

        assertThat(cards).hasSizeGreaterThan(1);
    }
}
