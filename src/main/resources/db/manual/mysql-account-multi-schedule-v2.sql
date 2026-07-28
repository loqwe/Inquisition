CREATE TABLE IF NOT EXISTS account_dispatch_time (
    id BIGINT NOT NULL AUTO_INCREMENT,
    account_id BIGINT NOT NULL,
    schedule_time TIME NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_account_dispatch_time (account_id, schedule_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT IGNORE INTO account_dispatch_time (account_id, schedule_time)
SELECT account_id, schedule_time
FROM account_dispatch_config
WHERE dispatch_mode = 'SCHEDULED'
  AND schedule_time IS NOT NULL;

DELIMITER $$
DROP PROCEDURE IF EXISTS account_multi_schedule_assert_table$$
DROP PROCEDURE IF EXISTS account_multi_schedule_assert_column$$
DROP PROCEDURE IF EXISTS account_multi_schedule_assert_index$$

CREATE PROCEDURE account_multi_schedule_assert_table(
    IN p_table_name VARCHAR(64)
)
BEGIN
    DECLARE v_match_count INT DEFAULT 0;

    SELECT COUNT(*) INTO v_match_count
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name = p_table_name
      AND UPPER(engine) = 'INNODB'
      AND table_collation = 'utf8mb4_0900_ai_ci';

    IF v_match_count <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Schema drift: account_dispatch_time storage';
    END IF;
END$$

CREATE PROCEDURE account_multi_schedule_assert_column(
    IN p_table_name VARCHAR(64),
    IN p_column_name VARCHAR(64),
    IN p_column_type VARCHAR(64),
    IN p_is_nullable VARCHAR(3),
    IN p_default_value VARCHAR(64),
    IN p_default_is_null TINYINT,
    IN p_extra_contains VARCHAR(64)
)
BEGIN
    DECLARE v_match_count INT DEFAULT 0;
    DECLARE v_message VARCHAR(128);

    SELECT COUNT(*) INTO v_match_count
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = p_table_name
      AND column_name = p_column_name
      AND LOWER(column_type) = LOWER(p_column_type)
      AND is_nullable = p_is_nullable
      AND (
          (p_default_is_null = 1 AND column_default IS NULL)
          OR (p_default_is_null = 0 AND LOWER(column_default) = LOWER(p_default_value))
      )
      AND (
          p_extra_contains IS NULL
          OR LOWER(extra) LIKE CONCAT('%', LOWER(p_extra_contains), '%')
      );

    IF v_match_count <> 1 THEN
        SET v_message = CONCAT('Schema drift: ', p_table_name, '.', p_column_name);
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = v_message;
    END IF;
END$$

CREATE PROCEDURE account_multi_schedule_assert_index(
    IN p_table_name VARCHAR(64),
    IN p_index_name VARCHAR(64),
    IN p_columns VARCHAR(255),
    IN p_non_unique INT
)
BEGIN
    DECLARE v_match_count INT DEFAULT 0;
    DECLARE v_message VARCHAR(128);

    SELECT COUNT(*) INTO v_match_count
    FROM (
        SELECT index_name
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = p_table_name
          AND index_name = p_index_name
        GROUP BY index_name
        HAVING GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') = p_columns
           AND COUNT(*) = 1 + LENGTH(p_columns) - LENGTH(REPLACE(p_columns, ',', ''))
           AND MIN(non_unique) = p_non_unique
           AND MAX(non_unique) = p_non_unique
           AND SUM(CASE WHEN UPPER(index_type) <> 'BTREE' THEN 1 ELSE 0 END) = 0
           AND SUM(CASE WHEN is_visible <> 'YES' THEN 1 ELSE 0 END) = 0
           AND SUM(CASE WHEN sub_part IS NOT NULL THEN 1 ELSE 0 END) = 0
    ) AS matching_indexes;

    IF v_match_count <> 1 THEN
        SET v_message = CONCAT('Schema drift: ', p_table_name, '.', p_index_name);
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = v_message;
    END IF;
END$$

CALL account_multi_schedule_assert_table('account_dispatch_time')$$
CALL account_multi_schedule_assert_column('account_dispatch_time', 'id', 'bigint', 'NO', NULL, 1, 'auto_increment')$$
CALL account_multi_schedule_assert_column('account_dispatch_time', 'account_id', 'bigint', 'NO', NULL, 1, NULL)$$
CALL account_multi_schedule_assert_column('account_dispatch_time', 'schedule_time', 'time', 'NO', NULL, 1, NULL)$$
CALL account_multi_schedule_assert_column('account_dispatch_time', 'created_at', 'datetime(6)', 'NO', 'CURRENT_TIMESTAMP(6)', 0, NULL)$$
CALL account_multi_schedule_assert_column('account_dispatch_time', 'updated_at', 'datetime(6)', 'NO', 'CURRENT_TIMESTAMP(6)', 0, 'on update current_timestamp(6)')$$
CALL account_multi_schedule_assert_index('account_dispatch_time', 'PRIMARY', 'id', 0)$$
CALL account_multi_schedule_assert_index('account_dispatch_time', 'uk_account_dispatch_time', 'account_id,schedule_time', 0)$$

DROP PROCEDURE account_multi_schedule_assert_index$$
DROP PROCEDURE account_multi_schedule_assert_column$$
DROP PROCEDURE account_multi_schedule_assert_table$$
DELIMITER ;
