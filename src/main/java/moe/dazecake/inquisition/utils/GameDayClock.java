package moe.dazecake.inquisition.utils;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

public final class GameDayClock {
    public static final ZoneId ZONE_ID = ZoneId.of("Asia/Shanghai");
    public static final LocalTime RESET_TIME = LocalTime.of(4, 0);

    private GameDayClock() {
    }

    public static LocalDateTime startOfGameDay(LocalDateTime now) {
        var date = now.toLocalTime().isBefore(RESET_TIME)
                ? now.toLocalDate().minusDays(1)
                : now.toLocalDate();
        return LocalDateTime.of(date, RESET_TIME);
    }

    public static LocalDate gameDay(LocalDateTime now) {
        return startOfGameDay(now).toLocalDate();
    }

    public static LocalDateTime now() {
        return LocalDateTime.now(ZONE_ID);
    }

    public static boolean isMissingValidLog(LocalDateTime now, LocalDateTime lastValidLogAt, long hours) {
        var gameDayStart = startOfGameDay(now);
        var anchor = lastValidLogAt == null || lastValidLogAt.isBefore(gameDayStart)
                ? gameDayStart
                : lastValidLogAt;
        return !now.isBefore(anchor.plus(Duration.ofHours(hours)));
    }
}
