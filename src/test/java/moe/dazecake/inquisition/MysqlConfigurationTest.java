package moe.dazecake.inquisition;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MysqlConfigurationTest {

    @Test
    void defaultMysqlProfileLoadsActableMapperXml() throws Exception {
        try (var stream = getClass().getResourceAsStream("/application.yml")) {
            assertTrue(stream != null, "application.yml must exist");
            var yaml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

            assertTrue(yaml.contains(
                    "mapper-locations: classpath*:com/gitee/sunchenbin/mybatis/actable/mapping/*/*.xml"),
                    "the default MySQL profile must load Actable mapper XML resources");
        }
    }

    @Test
    void defaultMysqlProfileDoesNotMutateSchemaAutomatically() throws Exception {
        try (var stream = getClass().getResourceAsStream("/application.yml")) {
            assertTrue(stream != null, "application.yml must exist");
            var yaml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

            assertTrue(yaml.contains("    auto: none"),
                    "manual migrations must remain authoritative for reliability tables");
        }
    }
}
