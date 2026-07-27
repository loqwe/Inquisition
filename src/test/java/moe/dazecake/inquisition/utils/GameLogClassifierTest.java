package moe.dazecake.inquisition.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameLogClassifierTest {

    @Test
    void loginAndTaskProgressLogsAreValid() {
        assertTrue(GameLogClassifier.isValidGameLog("INFO", "\u5f00\u59cb\u767b\u5f55", "device-1"));
        assertTrue(GameLogClassifier.isValidGameLog("INFO", "\u767b\u5f55\u6210\u529f", "device-1"));
        assertTrue(GameLogClassifier.isValidGameLog("INFO", "\u4efb\u52a1\u7ed3\u675f", "device-1"));
        assertTrue(GameLogClassifier.isGameStarted("\u767b\u5f55\u6210\u529f"));
    }

    @Test
    void systemAndRetryNoiseDoNotResetTheNineHourClock() {
        assertFalse(GameLogClassifier.isValidGameLog("WARN", "\u8d26\u53f7\u4e34\u65f6\u51b7\u5374", "SYSTEM"));
        assertFalse(GameLogClassifier.isValidGameLog("WARN", "\u4efb\u52a1\u5931\u8d25", "device-1"));
        assertFalse(GameLogClassifier.isValidGameLog("INFO", "\u6218\u672f\u91cd\u542f", "device-1"));
        assertFalse(GameLogClassifier.isValidGameLog("INFO", "\u901a\u77e5", "device-1"));
    }
}
