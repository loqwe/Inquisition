package moe.dazecake.inquisition.utils;

import com.baomidou.mybatisplus.annotation.DbType;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

public enum DatabaseDialect {
    MYSQL(DbType.MYSQL),
    SQLITE(DbType.SQLITE);

    private final DbType mybatisDbType;

    DatabaseDialect(DbType mybatisDbType) {
        this.mybatisDbType = mybatisDbType;
    }

    public DbType getMybatisDbType() {
        return mybatisDbType;
    }

    public static DatabaseDialect fromJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null) {
            throw new IllegalArgumentException("spring.datasource.url is required");
        }
        String normalized = jdbcUrl.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("jdbc:sqlite:")) {
            return SQLITE;
        }
        if (normalized.startsWith("jdbc:mysql:")) {
            return MYSQL;
        }
        throw new IllegalArgumentException("Unsupported database url: " + jdbcUrl);
    }

    public static Optional<Path> sqliteFilePath(String jdbcUrl) {
        if (fromJdbcUrl(jdbcUrl) != SQLITE) {
            return Optional.empty();
        }
        String path = jdbcUrl.substring("jdbc:sqlite:".length());
        int queryIndex = path.indexOf('?');
        if (queryIndex >= 0) {
            path = path.substring(0, queryIndex);
        }
        if (path.startsWith("file:")) {
            path = path.substring("file:".length());
        }
        if (path.isBlank() || ":memory:".equals(path)) {
            return Optional.empty();
        }
        return Optional.of(Path.of(path));
    }
}
