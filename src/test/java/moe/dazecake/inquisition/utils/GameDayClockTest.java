package moe.dazecake.inquisition.utils;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameDayClockTest {

    @Test
    void fourOClockStartsANewGameDay() {
        assertEquals(LocalDateTime.of(2026, 7, 18, 4, 0),
                GameDayClock.startOfGameDay(LocalDateTime.of(2026, 7, 18, 4, 0)));
        assertEquals(LocalDate.of(2026, 7, 18),
                GameDayClock.gameDay(LocalDateTime.of(2026, 7, 18, 4, 1)));
    }

    @Test
    void beforeFourBelongsToThePreviousGameDay() {
        assertEquals(LocalDateTime.of(2026, 7, 17, 4, 0),
                GameDayClock.startOfGameDay(LocalDateTime.of(2026, 7, 18, 3, 59, 59)));
        assertEquals(LocalDate.of(2026, 7, 17),
                GameDayClock.gameDay(LocalDateTime.of(2026, 7, 18, 3, 59, 59)));
    }

    @Test
    void missingLogBecomesEligibleOnlyAfterNineHoursFromGameDayStartOrLastLog() {
        var now = LocalDateTime.of(2026, 7, 18, 13, 0);
        assertTrue(GameDayClock.isMissingValidLog(now, null, 9));
        assertTrue(GameDayClock.isMissingValidLog(now,
                LocalDateTime.of(2026, 7, 18, 3, 59), 9));
        assertTrue(GameDayClock.isMissingValidLog(now,
                LocalDateTime.of(2026, 7, 18, 4, 0), 9));
    }

    @Test
    void recentValidLogDelaysTheNineHourCheck() {
        var now = LocalDateTime.of(2026, 7, 18, 13, 0);
        assertTrue(!GameDayClock.isMissingValidLog(now,
                LocalDateTime.of(2026, 7, 18, 8, 1), 9));
    }
}
