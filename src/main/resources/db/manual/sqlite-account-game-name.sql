-- Run once for an existing SQLite database before enabling game-name sync.
ALTER TABLE account ADD COLUMN game_name TEXT;
