DELIMITER $$
DROP PROCEDURE IF EXISTS account_scheduled_dispatch_drop_columns_if_present$$
CREATE PROCEDURE account_scheduled_dispatch_drop_columns_if_present(
    IN p_table_name VARCHAR(64)
)
BEGIN
    DECLARE v_dispatch_source_exists INT DEFAULT 0;
    DECLARE v_scheduled_run_id_exists INT DEFAULT 0;
    DECLARE v_separator VARCHAR(2) DEFAULT '';

    SELECT COUNT(*) INTO v_dispatch_source_exists
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = p_table_name
          AND column_name = 'dispatch_source';
    SELECT COUNT(*) INTO v_scheduled_run_id_exists
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = p_table_name
          AND column_name = 'scheduled_run_id';

    IF v_dispatch_source_exists > 0 OR v_scheduled_run_id_exists > 0 THEN
        SET @account_scheduled_dispatch_sql = CONCAT('ALTER TABLE `', p_table_name, '` ');
        IF v_scheduled_run_id_exists > 0 THEN
            SET @account_scheduled_dispatch_sql = CONCAT(
                @account_scheduled_dispatch_sql,
                'DROP COLUMN `scheduled_run_id`'
            );
            SET v_separator = ', ';
        END IF;
        IF v_dispatch_source_exists > 0 THEN
            SET @account_scheduled_dispatch_sql = CONCAT(
                @account_scheduled_dispatch_sql,
                v_separator,
                'DROP COLUMN `dispatch_source`'
            );
        END IF;
        SET @account_scheduled_dispatch_sql = CONCAT(
            @account_scheduled_dispatch_sql,
            ', ALGORITHM=INPLACE, LOCK=NONE'
        );
        PREPARE account_scheduled_dispatch_stmt FROM @account_scheduled_dispatch_sql;
        EXECUTE account_scheduled_dispatch_stmt;
        DEALLOCATE PREPARE account_scheduled_dispatch_stmt;
    END IF;
END$$

CALL account_scheduled_dispatch_drop_columns_if_present('task_assignment_history')$$
CALL account_scheduled_dispatch_drop_columns_if_present('task_assignment')$$

DROP PROCEDURE account_scheduled_dispatch_drop_columns_if_present$$
DELIMITER ;

DROP TABLE IF EXISTS account_scheduled_run;
DROP TABLE IF EXISTS account_dispatch_config;
