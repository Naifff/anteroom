-- Личные сообщения.
--
-- Поля получателя здесь нет и быть не должно, отправителя тоже: он подписывается ВНУТРИ
-- шифротекста. Любое из этих полей снаружи выдало бы пару, а скрыть её — весь смысл
-- рассылки на всю колоду.

CREATE TABLE direct (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  room_id     TEXT NOT NULL REFERENCES room(id) ON DELETE CASCADE,
  ciphertext  BLOB NOT NULL,   -- текст под случайным ключом сообщения
  envelopes   BLOB NOT NULL,   -- ровно 52 слота одной длины, порядок = номер карты
  created_at  INTEGER NOT NULL,
  expires_at  INTEGER NOT NULL
);

CREATE INDEX idx_direct_room    ON direct(room_id, id);
CREATE INDEX idx_direct_expires ON direct(expires_at);

-- Ключ личной переписки, свой на каждую эпоху комнаты. Отдельной таблицей, а не колонкой
-- в member_key: там wrapped_room_key объявлен NOT NULL, а обёртку кладёт другой участник —
-- свой же ключ переписки человек публикует сам, и порядок этих двух событий не задан.
--
-- Отдельный ключ, а не X25519 устройства: тот живёт вечно, и его утечка раскрыла бы всю
-- личную переписку задним числом, ротации ключа комнаты она не подчиняется.
CREATE TABLE dm_key (
  room_id     TEXT NOT NULL REFERENCES room(id) ON DELETE CASCADE,
  pubkey_sign TEXT NOT NULL,
  epoch       INTEGER NOT NULL,
  pubkey_dm   TEXT NOT NULL,
  PRIMARY KEY (room_id, pubkey_sign, epoch)
);

-- Администратор личную переписку не видит и модерировать её не может. Где это неприемлемо,
-- её выключают при создании комнаты — и потом не включают обратно: иначе участники,
-- вошедшие под обещание «здесь личного нет», узнают об изменении последними.
ALTER TABLE room ADD COLUMN direct_allowed INTEGER NOT NULL DEFAULT 1;
