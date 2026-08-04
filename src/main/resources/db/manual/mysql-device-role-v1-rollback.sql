-- Rollback for mysql-device-role-v1.sql.
ALTER TABLE `device` DROP COLUMN `device_role`;
