-- Владелец инстанса: тот, кто погасил owner-инвайт, напечатанный при первом старте.
--
-- Отдельная таблица, а не роль в member: до первой комнаты участия ещё нет, а право
-- заводить комнаты уже нужно. Инвайт без комнаты (room_id IS NULL) — это и есть
-- owner-инвайт; схема допускала NULL с самого начала.

CREATE TABLE instance_owner (
  pubkey_sign TEXT PRIMARY KEY REFERENCES device(pubkey_sign),
  granted_at  INTEGER NOT NULL
);
