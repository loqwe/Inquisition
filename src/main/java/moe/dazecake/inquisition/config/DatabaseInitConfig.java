package moe.dazecake.inquisition.config;

import com.alibaba.druid.pool.DruidDataSource;
import moe.dazecake.inquisition.utils.DatabaseDialect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Arrays;

@Configuration
@Primary
public class DatabaseInitConfig {
    private final Logger log = LoggerFactory.getLogger(DatabaseInitConfig.class);

    @Value("${spring.datasource.url}")
    private String datasourceUrl;
    @Value("${spring.datasource.driver-class-name}")
    private String driverClassName;
    @Value("${spring.datasource.username:}")
    private String username;
    @Value("${spring.datasource.password:}")
    private String password;

    @Bean
    public DataSource dataSource() {
        DruidDataSource datasource = new DruidDataSource();

        datasource.setUrl(datasourceUrl);
        datasource.setUsername(username);
        datasource.setPassword(password);
        datasource.setDriverClassName(driverClassName);
        datasource.setValidationQuery("SELECT 1");

        try {
            Class.forName(driverClassName);
            DatabaseDialect dialect = DatabaseDialect.fromJdbcUrl(datasourceUrl);
            if (dialect == DatabaseDialect.SQLITE) {
                configureSqlite(datasource);
            } else if (dialect == DatabaseDialect.MYSQL) {
                createMysqlDatabaseIfMissing();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Database initialization failed", e);
        }


        return datasource;
    }

    private void configureSqlite(DruidDataSource datasource) throws Exception {
        var sqliteFilePath = DatabaseDialect.sqliteFilePath(datasourceUrl);
        if (sqliteFilePath.isPresent()) {
            Path parent = sqliteFilePath.get().toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        }
        datasource.setInitialSize(1);
        datasource.setMinIdle(1);
        datasource.setMaxActive(1);
        datasource.setMaxWait(10000);
        datasource.setConnectionInitSqls(Arrays.asList(
                "PRAGMA busy_timeout = 5000",
                "PRAGMA foreign_keys = ON",
                "PRAGMA journal_mode = WAL"
        ));
        log.info("【审判庭初始化】 SQLite 数据库已启用: " + datasourceUrl);
    }

    private void createMysqlDatabaseIfMissing() throws Exception {
        String url01 = datasourceUrl.contains("?") ? datasourceUrl.substring(0, datasourceUrl.indexOf("?")) : datasourceUrl;

        String url02 = url01.substring(0, url01.lastIndexOf("/"));

        String datasourceName = url01.substring(url01.lastIndexOf("/") + 1);
        // 连接已经存在的数据库，如：mysql
        Connection connection = DriverManager.getConnection(url02, username, password);
        Statement statement = connection.createStatement();

        // 创建数据库
        statement.executeUpdate("create database if not exists `" + datasourceName + "` default character set " +
                "utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        log.info("【审判庭初始化】 创建数据库成功");
        statement.close();
        connection.close();
    }
}
