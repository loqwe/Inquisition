package moe.dazecake.inquisition.service.impl;

import moe.dazecake.inquisition.model.entity.AccountEntity;
import moe.dazecake.inquisition.model.entity.ActivationDateSet.ActivateConfig;
import moe.dazecake.inquisition.model.entity.ActivationDateSet.ActivationDate;
import moe.dazecake.inquisition.utils.GameDayClock;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
public class AccountScheduleCalculator {

    public LocalDateTime nextOccurrence(AccountEntity account, LocalTime time,
                                        LocalDateTime strictlyAfter) {
        Objects.requireNonNull(time, "time");
        return nextOccurrence(account, List.of(time), strictlyAfter);
    }

    public LocalDateTime nextOccurrence(AccountEntity account, Collection<LocalTime> times,
                                        LocalDateTime strictlyAfter) {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(times, "times");
        Objects.requireNonNull(strictlyAfter, "strictlyAfter");
        var orderedTimes = times.stream()
                .map(time -> Objects.requireNonNull(time, "schedule time"))
                .distinct()
                .sorted()
                .collect(Collectors.toList());
        if (orderedTimes.isEmpty()) {
            throw new IllegalArgumentException("At least one schedule time is required");
        }
        var active = account.getActive() == null ? new ActivationDate() : account.getActive();
        if (!hasEnabledWeekday(active)) {
            throw new IllegalArgumentException("At least one active weekday must be enabled");
        }

        var boundary = strictlyAfter.atZone(GameDayClock.ZONE_ID);
        for (int daysAhead = 0; daysAhead <= 7; daysAhead++) {
            var date = boundary.toLocalDate().plusDays(daysAhead);
            if (!isEnabled(active, date.getDayOfWeek())) {
                continue;
            }
            for (var time : orderedTimes) {
                var candidate = ZonedDateTime.of(date, time, GameDayClock.ZONE_ID);
                if (candidate.isAfter(boundary)) {
                    return candidate.toLocalDateTime();
                }
            }
        }
        throw new IllegalStateException("Unable to calculate the next enabled occurrence");
    }

    public boolean belongsToCurrentGameDay(LocalDateTime scheduledFor, LocalDateTime now) {
        Objects.requireNonNull(scheduledFor, "scheduledFor");
        Objects.requireNonNull(now, "now");
        return GameDayClock.gameDay(scheduledFor).equals(GameDayClock.gameDay(now));
    }

    private boolean hasEnabledWeekday(ActivationDate active) {
        if (active == null) {
            return false;
        }
        for (DayOfWeek day : DayOfWeek.values()) {
            if (isEnabled(active, day)) {
                return true;
            }
        }
        return false;
    }

    private boolean isEnabled(ActivationDate active, DayOfWeek day) {
        if (active == null) {
            return false;
        }
        ActivateConfig config;
        switch (day) {
            case MONDAY:
                config = active.getMonday();
                break;
            case TUESDAY:
                config = active.getTuesday();
                break;
            case WEDNESDAY:
                config = active.getWednesday();
                break;
            case THURSDAY:
                config = active.getThursday();
                break;
            case FRIDAY:
                config = active.getFriday();
                break;
            case SATURDAY:
                config = active.getSaturday();
                break;
            case SUNDAY:
                config = active.getSunday();
                break;
            default:
                throw new IllegalArgumentException("Unsupported weekday: " + day);
        }
        return config != null && config.isEnable();
    }
}
