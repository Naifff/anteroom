-- Режим уборки (auto_vacuum = INCREMENTAL) задан не здесь, а в JDBC-URL: SQLite принимает
-- этот PRAGMA только на пустой базе и вне транзакции, а Flyway выполняет миграцию внутри
-- транзакции и молча получил бы no-op. Поменять режим потом можно только полным VACUUM.

CREATE TABLE room (
  id            TEXT PRIMARY KEY,
  key_epoch     INTEGER NOT NULL DEFAULT 1,
  default_ttl   INTEGER NOT NULL DEFAULT 172800,   -- 48 ч
  max_ttl       INTEGER NOT NULL DEFAULT 432000,   -- 5 суток, потолок
  seats_taken   INTEGER NOT NULL DEFAULT 0,        -- карт роздано; 52 = колода кончилась
  created_at    INTEGER NOT NULL
);

CREATE TABLE device (
  pubkey_sign   TEXT PRIMARY KEY,      -- Ed25519, base64url
  pubkey_box    TEXT NOT NULL,         -- X25519
  created_at    INTEGER NOT NULL,
  label         TEXT
);

-- участие: одна строка на человека в комнате, эпохи её НЕ размножают
CREATE TABLE member (
  room_id       TEXT NOT NULL REFERENCES room(id) ON DELETE CASCADE,
  pubkey_sign   TEXT NOT NULL REFERENCES device(pubkey_sign),
  role          TEXT NOT NULL,         -- owner | admin | member; в ленте не показывается
  card          INTEGER NOT NULL,      -- 0..51; выбывшая карта в колоду не возвращается
  invited_by    TEXT,                  -- для каскадного отзыва
  joined_at     INTEGER NOT NULL,
  left_at       INTEGER,               -- при выходе строка остаётся, карта не освобождается
  PRIMARY KEY (room_id, pubkey_sign)
);
CREATE UNIQUE INDEX idx_member_card ON member(room_id, card);

-- обёртки ключа комнаты: по строке на участника и эпоху
CREATE TABLE member_key (
  room_id          TEXT NOT NULL REFERENCES room(id) ON DELETE CASCADE,
  pubkey_sign      TEXT NOT NULL,
  epoch            INTEGER NOT NULL,
  wrapped_room_key BLOB NOT NULL,
  PRIMARY KEY (room_id, pubkey_sign, epoch)
);

CREATE TABLE invite (
  token_hash    TEXT PRIMARY KEY,      -- SHA-256 от токена
  room_id       TEXT REFERENCES room(id) ON DELETE CASCADE,
  role          TEXT NOT NULL,
  created_by    TEXT,
  wrapped_key   BLOB NOT NULL,         -- room_key под эфемерным ключом инвайта
  expires_at    INTEGER NOT NULL,
  uses_left     INTEGER NOT NULL
);

CREATE TABLE message (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  room_id       TEXT NOT NULL REFERENCES room(id) ON DELETE CASCADE,
  sender        TEXT NOT NULL,
  epoch         INTEGER NOT NULL,
  ciphertext    BLOB NOT NULL,
  created_at    INTEGER NOT NULL,
  expires_at    INTEGER NOT NULL
);

CREATE TABLE onetime (
  token_hash    TEXT PRIMARY KEY,
  room_id       TEXT NOT NULL REFERENCES room(id) ON DELETE CASCADE,  -- иначе wipe комнаты их не заденет
  ciphertext    BLOB NOT NULL,
  expires_at    INTEGER NOT NULL
);

CREATE INDEX idx_message_room_id  ON message(room_id, id);
CREATE INDEX idx_message_expires  ON message(expires_at);
CREATE INDEX idx_invite_expires   ON invite(expires_at);
CREATE INDEX idx_onetime_expires  ON onetime(expires_at);
