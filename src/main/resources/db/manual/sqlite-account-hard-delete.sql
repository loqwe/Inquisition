-- Manual migration for local SQLite deployments.
-- Run after backing up the database.

DELETE FROM account WHERE IFNULL(`delete`, 0) = 1;

UPDATE account
SET account = NULL
WHERE account = '';

SELECT account, COUNT(*) AS duplicate_count
FROM account
WHERE account IS NOT NULL AND account <> ''
GROUP BY account
HAVING duplicate_count > 1;

-- Only run this after the duplicate query above returns no rows.
CREATE UNIQUE INDEX IF NOT EXISTS uk_account_account
ON account(account)
WHERE account IS NOT NULL AND account <> '';
