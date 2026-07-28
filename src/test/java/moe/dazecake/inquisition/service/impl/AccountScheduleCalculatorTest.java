package moe.dazecake.inquisition.service.impl;

import moe.dazecake.inquisition.model.entity.AccountEntity;
import moe.dazecake.inquisition.model.entity.ActivationDateSet.ActivateConfig;
import moe.dazecake.inquisition.model.entity.ActivationDateSet.ActivationDate;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountScheduleCalculatorTest {

    private final AccountScheduleCalculator calculator = new AccountScheduleCalculator();

    @Test
    void returnsTodaysEnabledOccurrenceWhenItIsStillAhead() {
        var account = accountEnabledOn(DayOfWeek.MONDAY);

        var next = calculator.nextOccurrence(account, LocalTime.of(19, 30),
                LocalDateTime.of(2026, 7, 27, 10, 0));

        assertEquals(LocalDateTime.of(2026, 7, 27, 19, 30), next);
    }

    @Test
    void skipsTodaysOccurrenceWhenItHasPassed() {
        var account = accountEnabledOn(DayOfWeek.MONDAY);

        var next = calculator.nextOccurrence(account, LocalTime.of(19, 30),
                LocalDateTime.of(2026, 7, 27, 19, 31));

        assertEquals(LocalDateTime.of(2026, 8, 3, 19, 30), next);
    }

    @Test
    void returnedOccurrenceIsStrictlyLaterThanAnEqualBoundary() {
        var account = accountEnabledOn(DayOfWeek.MONDAY);

        var next = calculator.nextOccurrence(account, LocalTime.of(19, 30),
                LocalDateTime.of(2026, 7, 27, 19, 30));

        assertEquals(LocalDateTime.of(2026, 8, 3, 19, 30), next);
    }

    @Test
    void selectsTheEarliestConfiguredTimeStillAheadToday() {
        var account = accountEnabledOn(DayOfWeek.MONDAY);

        var next = calculator.nextOccurrence(account,
                List.of(LocalTime.of(19, 30), LocalTime.of(8, 0), LocalTime.of(14, 0)),
                LocalDateTime.of(2026, 7, 27, 10, 0));

        assertEquals(LocalDateTime.of(2026, 7, 27, 14, 0), next);
    }

    @Test
    void multipleTimesRemainStrictlyLaterThanAnEqualBoundary() {
        var account = accountEnabledOn(DayOfWeek.MONDAY);

        var next = calculator.nextOccurrence(account,
                List.of(LocalTime.of(8, 0), LocalTime.of(14, 0), LocalTime.of(19, 30)),
                LocalDateTime.of(2026, 7, 27, 14, 0));

        assertEquals(LocalDateTime.of(2026, 7, 27, 19, 30), next);
    }

    @Test
    void multipleTimesCrossToTheFirstTimeOnTheNextEnabledDay() {
        var account = accountEnabledOn(DayOfWeek.MONDAY);

        var next = calculator.nextOccurrence(account,
                List.of(LocalTime.of(19, 30), LocalTime.of(8, 0), LocalTime.of(14, 0)),
                LocalDateTime.of(2026, 7, 27, 20, 0));

        assertEquals(LocalDateTime.of(2026, 8, 3, 8, 0), next);
    }

    @Test
    void rejectsAnEmptyTimeCollection() {
        var account = accountEnabledOn(DayOfWeek.MONDAY);

        assertThrows(IllegalArgumentException.class,
                () -> calculator.nextOccurrence(account, List.of(),
                        LocalDateTime.of(2026, 7, 27, 10, 0)));
    }

    @Test
    void crossesTheWeekBoundaryToTheNextEnabledDay() {
        var account = accountEnabledOn(DayOfWeek.FRIDAY);

        var next = calculator.nextOccurrence(account, LocalTime.of(8, 15),
                LocalDateTime.of(2026, 7, 25, 12, 0));

        assertEquals(LocalDateTime.of(2026, 7, 31, 8, 15), next);
    }

    @Test
    void nullActivationUsesTheExistingSevenDayDefaults() {
        var account = new AccountEntity().setActive(null);

        var next = calculator.nextOccurrence(account, LocalTime.of(19, 30),
                LocalDateTime.of(2026, 7, 27, 20, 0));

        assertEquals(LocalDateTime.of(2026, 7, 28, 19, 30), next);
    }

    @Test
    void findsAStrictlyFutureOccurrenceAcrossMultipleDisabledDays() {
        var account = accountEnabledOn(DayOfWeek.THURSDAY);
        var boundary = LocalDateTime.of(2026, 7, 27, 20, 0);

        var next = calculator.nextOccurrence(account, LocalTime.of(8, 15), boundary);

        assertEquals(LocalDateTime.of(2026, 7, 30, 8, 15), next);
        assertTrue(next.isAfter(boundary));
    }

    @Test
    void twoThirtyBelongsToThePreviousGameDayUntilFourOClock() {
        var scheduledFor = LocalDateTime.of(2026, 7, 28, 2, 30);

        assertTrue(calculator.belongsToCurrentGameDay(scheduledFor,
                LocalDateTime.of(2026, 7, 28, 3, 59)));
        assertFalse(calculator.belongsToCurrentGameDay(scheduledFor,
                LocalDateTime.of(2026, 7, 28, 4, 0)));
    }

    @Test
    void lateEveningAndBeforeResetBelongToTheSameGameDay() {
        var scheduledFor = LocalDateTime.of(2026, 7, 27, 19, 30);

        assertTrue(calculator.belongsToCurrentGameDay(scheduledFor,
                LocalDateTime.of(2026, 7, 28, 3, 59)));
        assertFalse(calculator.belongsToCurrentGameDay(scheduledFor,
                LocalDateTime.of(2026, 7, 28, 4, 0)));
    }

    @Test
    void usesShanghaiWallTimeInsteadOfTheHostDefaultZone() {
        var original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            var account = accountEnabledOn(DayOfWeek.MONDAY);

            var next = calculator.nextOccurrence(account, LocalTime.of(19, 30),
                    LocalDateTime.of(2026, 7, 27, 10, 0));

            assertEquals(LocalDateTime.of(2026, 7, 27, 19, 30), next);
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void rejectsAnAccountWithoutAnEnabledWeekday() {
        var account = accountEnabledOn();

        assertThrows(IllegalArgumentException.class,
                () -> calculator.nextOccurrence(account, LocalTime.of(19, 30),
                        LocalDateTime.of(2026, 7, 27, 10, 0)));
    }

    private AccountEntity accountEnabledOn(DayOfWeek... enabledDays) {
        var active = new ActivationDate();
        activationConfigs(active).forEach(config -> config.setEnable(false));
        for (DayOfWeek day : enabledDays) {
            configFor(active, day).setEnable(true);
        }
        return new AccountEntity().setActive(active);
    }

    private List<ActivateConfig> activationConfigs(ActivationDate active) {
        return List.of(active.getMonday(), active.getTuesday(), active.getWednesday(),
                active.getThursday(), active.getFriday(), active.getSaturday(), active.getSunday());
    }

    private ActivateConfig configFor(ActivationDate active, DayOfWeek day) {
        switch (day) {
            case MONDAY:
                return active.getMonday();
            case TUESDAY:
                return active.getTuesday();
            case WEDNESDAY:
                return active.getWednesday();
            case THURSDAY:
                return active.getThursday();
            case FRIDAY:
                return active.getFriday();
            case SATURDAY:
                return active.getSaturday();
            case SUNDAY:
                return active.getSunday();
            default:
                throw new IllegalArgumentException("Unsupported weekday: " + day);
        }
    }
}
