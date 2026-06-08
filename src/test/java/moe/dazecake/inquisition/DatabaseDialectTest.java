package moe.dazecake.inquisition;

import com.baomidou.mybatisplus.annotation.DbType;
import moe.dazecake.inquisition.utils.DatabaseDialect;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DatabaseDialectTest {

    @Test
    void detectsSqliteFromJdbcUrl() {
        assertEquals(DatabaseDialect.SQLITE, DatabaseDialect.fromJdbcUrl("jdbc:sqlite:./data/inquisition.db"));
        assertEquals(DbType.SQLITE, DatabaseDialect.fromJdbcUrl("jdbc:sqlite::memory:").getMybatisDbType());
    }

    @Test
    void detectsMysqlFromJdbcUrl() {
        assertEquals(DatabaseDialect.MYSQL, DatabaseDialect.fromJdbcUrl("jdbc:mysql://127.0.0.1:3306/inquisition"));
        assertEquals(DbType.MYSQL, DatabaseDialect.fromJdbcUrl("jdbc:mysql://127.0.0.1:3306/inquisition").getMybatisDbType());
    }
}
