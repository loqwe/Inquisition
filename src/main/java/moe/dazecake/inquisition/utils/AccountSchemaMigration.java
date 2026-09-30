package moe.dazecake.inquisition.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;

@Component
@Slf4j
public class AccountSchemaMigration implements ApplicationRunner {

    @Resource
    private JdbcTemplate jdbc;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (jdbc.getDataSource() == null) return;
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            if (hasColumn(metadata, connection.getCatalog(), "account", "game_name") ||
                    hasColumn(metadata, connection.getCatalog(), "ACCOUNT", "GAME_NAME")) {
                return;
            }
            String product = metadata.getDatabaseProductName().toLowerCase();
            jdbc.execute(product.contains("mysql")
                    ? "ALTER TABLE account ADD COLUMN game_name VARCHAR(128) NULL AFTER name"
                    : "ALTER TABLE account ADD COLUMN game_name TEXT");
            log.info("[Schema] added account.game_name");
        }
    }

    private boolean hasColumn(DatabaseMetaData metadata, String catalog, String table, String column) throws Exception {
        try (ResultSet columns = metadata.getColumns(catalog, null, table, column)) {
            return columns.next();
        }
    }
}
