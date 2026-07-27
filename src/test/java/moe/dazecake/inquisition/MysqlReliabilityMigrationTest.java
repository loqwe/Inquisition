package moe.dazecake.inquisition;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MysqlReliabilityMigrationTest {

    @Test
    void topLevelCallsUseTheActiveMysqlDelimiter() throws Exception {
        try (var stream = getClass().getResourceAsStream("/db/manual/mysql-reliability-v1.sql")) {
            assertTrue(stream != null, "migration resource must exist");
            var sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            var delimiterSection = sql.substring(sql.indexOf("DELIMITER $$"), sql.indexOf("DELIMITER ;"));

            assertFalse(delimiterSection.matches("(?s).*CALL\\s+[^\\r\\n]+;\\s*(?:\\r?\\n).*"),
                    "top-level CALL statements must end with $$ while DELIMITER $$ is active");
        }
    }
}
