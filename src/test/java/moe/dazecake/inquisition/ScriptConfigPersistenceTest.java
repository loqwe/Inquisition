package moe.dazecake.inquisition;

import com.google.gson.Gson;
import moe.dazecake.inquisition.model.entity.ConfigEntitySet.ConfigEntity;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScriptConfigPersistenceTest {
    @Test
    void scriptSelectionAndAdvancedConfigSurviveJsonRoundTrip() {
        ConfigEntity source = new ConfigEntity();
        source.setScript(Map.of(
                "schemaVersion", 1,
                "selection", Map.of("mail_claim", true, "route_probe", false),
                "advancedConfig", Map.of("voucher_max_spend", 30000000)
        ));

        ConfigEntity restored = new Gson().fromJson(new Gson().toJson(source), ConfigEntity.class);
        assertEquals(true, ((Map<?, ?>) restored.getScript().get("selection")).get("mail_claim"));
        assertEquals(1.0, restored.getScript().get("schemaVersion"));
    }
}
