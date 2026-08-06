package org.anteroom.room;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import org.anteroom.device.DeviceService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = "app.data-dir=build/test-data-epoch")
class KeyEpochServiceTest {

    private static final Path DATA_DIR = Path.of("build/test-data-epoch");

    @Autowired
    private RoomService rooms;

    @Autowired
    private KeyEpochService epochs;

    @Autowired
    private DeviceService devices;

    @Autowired
    private JdbcTemplate jdbc;

    private String roomId;

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

        devices.rememberSigningKey("owner");
        roomId = rooms.create("owner", 3600, 86400);
    }

    @Test
    void keepsWrappedKeyForItsOwner() {
        epochs.storeWrappedKey(roomId, "owner", 1, new byte[] { 1, 2, 3 });

        assertThat(epochs.wrappedKey(roomId, "owner", 1)).containsExactly(1, 2, 3);
    }

    @Test
    void returnsNothingForStrangerDevice() {
        // Третье устройство, не состоящее в комнате, обёртку получить не должно —
        // иначе ему достанется ключ, которым читается вся лента.
        epochs.storeWrappedKey(roomId, "owner", 1, new byte[] { 1, 2, 3 });

        assertThat(epochs.wrappedKey(roomId, "stranger", 1)).isNull();
    }

    @Test
    void separatesEpochs() {
        epochs.storeWrappedKey(roomId, "owner", 1, new byte[] { 1 });
        epochs.storeWrappedKey(roomId, "owner", 2, new byte[] { 2 });

        assertThat(epochs.wrappedKey(roomId, "owner", 1)).containsExactly(1);
        assertThat(epochs.wrappedKey(roomId, "owner", 2)).containsExactly(2);
    }

    @Test
    void returnsNothingForEpochWithoutWrapper() {
        epochs.storeWrappedKey(roomId, "owner", 1, new byte[] { 1 });

        assertThat(epochs.wrappedKey(roomId, "owner", 2)).isNull();
    }

    @Test
    void bumpsEpochOnRotation() {
        assertThat(epochs.rotate(roomId)).isEqualTo(2);
        assertThat(rooms.find(roomId).keyEpoch()).isEqualTo(2);
    }

    @Test
    void keepsOldWrappersAfterRotation() {
        // Оставшиеся участники обязаны дочитать историю прежней эпохи: она ещё не протухла,
        // а ключа от неё, кроме этой обёртки, взять больше неоткуда.
        epochs.storeWrappedKey(roomId, "owner", 1, new byte[] { 1 });

        epochs.rotate(roomId);

        assertThat(epochs.wrappedKey(roomId, "owner", 1)).containsExactly(1);
    }

    @Test
    void replacesWrapperOfSameEpoch() {
        epochs.storeWrappedKey(roomId, "owner", 1, new byte[] { 1 });
        epochs.storeWrappedKey(roomId, "owner", 1, new byte[] { 9 });

        assertThat(epochs.wrappedKey(roomId, "owner", 1)).containsExactly(9);
    }

    @Test
    void listsDevicesAwaitingWrapperForEpoch() {
        // Тому, кто раздаёт ключ, нужен список: кому обёртки новой эпохи ещё не положили.
        devices.rememberSigningKey("member-a");
        rooms.join(roomId, "member-a", "member", "owner");
        int epoch = epochs.rotate(roomId);
        epochs.storeWrappedKey(roomId, "owner", epoch, new byte[] { 1 });

        assertThat(epochs.membersWithoutWrapper(roomId, epoch)).containsExactly("member-a");
    }

    @Test
    void doesNotAwaitWrapperForDepartedMember() {
        devices.rememberSigningKey("member-b");
        rooms.join(roomId, "member-b", "member", "owner");
        rooms.remove(roomId, "member-b");
        int epoch = epochs.rotate(roomId);
        epochs.storeWrappedKey(roomId, "owner", epoch, new byte[] { 1 });

        // Исключённый новую эпоху читать не должен: в этом и смысл ротации.
        assertThat(epochs.membersWithoutWrapper(roomId, epoch)).isEmpty();
    }

    @Test
    void dropsWrappersWithTheRoom() {
        epochs.storeWrappedKey(roomId, "owner", 1, new byte[] { 1 });

        jdbc.update("DELETE FROM room WHERE id = ?", roomId);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM member_key WHERE room_id = ?", Integer.class, roomId)).isZero();
    }
}
