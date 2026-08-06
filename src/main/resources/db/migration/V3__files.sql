-- Метаданные вложений. Ни имени файла, ни MIME-типа, ни ключа здесь нет — сознательно:
-- всё это едет внутри сообщения, зашифрованного room_key. Сервер видит только размер
-- шифротекста и время; размер утекает и скрыть его нечем, про это надо просто знать.

CREATE TABLE file (
  id            TEXT PRIMARY KEY,      -- случайный, не производный от имени
  room_id       TEXT NOT NULL REFERENCES room(id) ON DELETE CASCADE,
  uploader      TEXT NOT NULL,
  size_bytes    INTEGER NOT NULL,      -- размер шифротекста, не открытого текста
  created_at    INTEGER NOT NULL,
  expires_at    INTEGER NOT NULL
);

-- Каскад сносит строки при удалении комнаты, но блобы на диске он не видит: их подберёт
-- скан осиротевших при следующем старте.
CREATE INDEX idx_file_expires ON file(expires_at);
CREATE INDEX idx_file_room    ON file(room_id);
