package moe.dazecake.inquisition.service.impl;

import moe.dazecake.inquisition.mapper.AccountDispatchConfigMapper;
import moe.dazecake.inquisition.mapper.AccountMapper;
import moe.dazecake.inquisition.model.entity.AccountDispatchConfigEntity;
import moe.dazecake.inquisition.model.entity.AccountEntity;
import moe.dazecake.inquisition.model.entity.AccountScheduledRunEntity;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountScheduledDispatchServiceTest {

    @Test
    void sameGameDayCatchUpCreatesOneWaitingRunAndClearsTheDueTime() {
        var service = service();
        var now = LocalDateTime.of(2026, 7, 28, 20, 0);
        var dueAt = LocalDateTime.of(2026, 7, 28, 19, 30);
        var config = dueConfig(7L, dueAt);
        var account = validAccount(7L, now);
        var created = run(41L, 7L, dueAt, AccountScheduledRunService.STATUS_WAITING);
        stubDue(service, now, config, account);
        when(service.runService.findActiveByAccount(7L)).thenReturn(java.util.Optional.empty());
        when(service.calculator.belongsToCurrentGameDay(dueAt, now)).thenReturn(true);
        when(service.runService.createWaiting(7L, dueAt)).thenReturn(created);
        when(service.configMapper.clearDue(7L, dueAt)).thenReturn(1);
        when(service.runService.findDispatchable(now)).thenReturn(List.of(created));

        var result = service.scan(now);

        assertEquals(List.of(created), result);
        verify(service.configMapper).selectByIdForUpdate(7L);
        verify(service.runService).createWaiting(7L, dueAt);
        verify(service.configMapper).clearDue(7L, dueAt);
    }

    @Test
    void oldGameDayOccurrenceAdvancesStrictlyIntoTheFutureWithoutCreatingARun() {
        var service = service();
        var now = LocalDateTime.of(2026, 7, 29, 4, 5);
        var dueAt = LocalDateTime.of(2026, 7, 28, 19, 30);
        var next = LocalDateTime.of(2026, 7, 29, 19, 30);
        var config = dueConfig(7L, dueAt);
        var account = validAccount(7L, now);
        stubDue(service, now, config, account);
        when(service.runService.findActiveByAccount(7L)).thenReturn(java.util.Optional.empty());
        when(service.calculator.nextOccurrence(account, config.getScheduleTime(), now)).thenReturn(next);
        when(service.configMapper.advanceDue(7L, dueAt, next)).thenReturn(1);

        assertTrue(service.scan(now).isEmpty());

        verify(service.runService, never()).createWaiting(any(), any());
        verify(service.configMapper).advanceDue(7L, dueAt, next);
    }

    @Test
    void existingActiveRunSurvivesAcrossFourOClockWithoutASecondInstance() {
        var service = service();
        var dueAt = LocalDateTime.of(2026, 7, 28, 3, 30);
        var now = LocalDateTime.of(2026, 7, 28, 4, 5);
        var config = dueConfig(7L, dueAt);
        var account = validAccount(7L, now);
        var existing = run(41L, 7L, dueAt, AccountScheduledRunService.STATUS_WAITING);
        stubDue(service, now, config, account);
        when(service.runService.findActiveByAccount(7L)).thenReturn(java.util.Optional.of(existing));
        when(service.configMapper.clearDue(7L, dueAt)).thenReturn(1);
        when(service.runService.findDispatchable(now)).thenReturn(List.of(existing));

        assertEquals(List.of(existing), service.scan(now));

        verify(service.runService, never()).createWaiting(any(), any());
        verify(service.runService, never()).cancel(any());
        verify(service.runService, never()).fail(any(), any());
        verify(service.calculator, never()).nextOccurrence(any(), any(), any());
    }

    @Test
    void frozenAccountAdvancesToTheNextFutureOccurrence() {
        var service = service();
        var now = LocalDateTime.of(2026, 7, 28, 20, 0);
        var dueAt = now.minusMinutes(30);
        var next = now.plusDays(1).withHour(19).withMinute(30);
        var config = dueConfig(7L, dueAt);
        var account = validAccount(7L, now).setFreeze(1);
        stubDue(service, now, config, account);
        when(service.calculator.nextOccurrence(account, config.getScheduleTime(), now)).thenReturn(next);
        when(service.configMapper.advanceDue(7L, dueAt, next)).thenReturn(1);

        assertTrue(service.scan(now).isEmpty());

        verify(service.configMapper).advanceDue(7L, dueAt, next);
        verify(service.runService, never()).createWaiting(any(), any());
    }

    @Test
    void deletedAccountClearsTheNextOccurrence() {
        var service = service();
        var now = LocalDateTime.of(2026, 7, 28, 20, 0);
        var dueAt = now.minusMinutes(30);
        var config = dueConfig(7L, dueAt);
        var account = validAccount(7L, now).setDelete(1);
        stubDue(service, now, config, account);
        when(service.configMapper.clearDue(7L, dueAt)).thenReturn(1);

        assertTrue(service.scan(now).isEmpty());

        verify(service.configMapper).clearDue(7L, dueAt);
        verify(service.runService, never()).createWaiting(any(), any());
    }

    @Test
    void expiredAccountClearsTheNextOccurrence() {
        var service = service();
        var now = LocalDateTime.of(2026, 7, 28, 20, 0);
        var dueAt = now.minusMinutes(30);
        var config = dueConfig(7L, dueAt);
        var account = validAccount(7L, now).setExpireTime(now);
        stubDue(service, now, config, account);
        when(service.configMapper.clearDue(7L, dueAt)).thenReturn(1);

        assertTrue(service.scan(now).isEmpty());

        verify(service.configMapper).clearDue(7L, dueAt);
        verify(service.runService, never()).createWaiting(any(), any());
    }

    @Test
    void nonDailyAccountClearsTheInvalidScheduledOccurrence() {
        var service = service();
        var now = LocalDateTime.of(2026, 7, 28, 20, 0);
        var dueAt = now.minusMinutes(30);
        var config = dueConfig(7L, dueAt);
        var account = validAccount(7L, now).setTaskType("login");
        stubDue(service, now, config, account);
        when(service.configMapper.clearDue(7L, dueAt)).thenReturn(1);

        assertTrue(service.scan(now).isEmpty());

        verify(service.configMapper).clearDue(7L, dueAt);
        verify(service.runService, never()).createWaiting(any(), any());
    }

    @Test
    void pendingActivationIsLockedAndSkippedWithoutChangingItsSchedule() {
        var service = service();
        var now = LocalDateTime.of(2026, 7, 28, 20, 0);
        var dueAt = now.minusMinutes(30);
        var config = dueConfig(7L, dueAt).setActivationPending(1);
        when(service.configMapper.selectDue(now)).thenReturn(List.of(config));
        when(service.configMapper.selectByIdForUpdate(7L)).thenReturn(config);

        assertTrue(service.scan(now).isEmpty());

        verify(service.accountMapper, never()).selectById(any());
        verify(service.configMapper, never()).clearDue(any(), any());
        verify(service.configMapper, never()).advanceDue(any(), any(), any());
    }

    @Test
    void restoreReturnsWaitingAndDueRetryRunsWithoutRecheckingCalendarOrAccount() {
        var service = service();
        var now = LocalDateTime.of(2026, 7, 28, 4, 5);
        var waiting = run(41L, 7L, now.minusDays(2), AccountScheduledRunService.STATUS_WAITING);
        var retry = run(42L, 8L, now.minusDays(3), AccountScheduledRunService.STATUS_RETRY_WAIT)
                .setNextRetryAt(now);
        when(service.runService.findDispatchable(now)).thenReturn(List.of(waiting, retry));

        assertEquals(List.of(waiting, retry), service.restoreDispatchable(now));

        verify(service.accountMapper, never()).selectById(any());
        verify(service.calculator, never()).belongsToCurrentGameDay(any(), any());
        verify(service.calculator, never()).nextOccurrence(any(), any(), any());
    }

    @Test
    void scannerRejectsAStaleNarrowUpdateInsteadOfSilentlyLosingTheOccurrence() {
        var service = service();
        var now = LocalDateTime.of(2026, 7, 28, 20, 0);
        var dueAt = now.minusMinutes(30);
        var config = dueConfig(7L, dueAt);
        var account = validAccount(7L, now).setDelete(1);
        stubDue(service, now, config, account);
        when(service.configMapper.clearDue(7L, dueAt)).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> service.scan(now));
    }

    @Test
    void mapperContractUsesDueSelectionRowLockingAndExpectedValueUpdates() throws Exception {
        var dueSql = sql(AccountDispatchConfigMapper.class
                .getMethod("selectDue", LocalDateTime.class).getAnnotation(Select.class).value());
        var lockSql = sql(AccountDispatchConfigMapper.class
                .getMethod("selectByIdForUpdate", Long.class).getAnnotation(Select.class).value());
        var clearSql = sql(AccountDispatchConfigMapper.class
                .getMethod("clearDue", Long.class, LocalDateTime.class).getAnnotation(Update.class).value());
        var advanceSql = sql(AccountDispatchConfigMapper.class
                .getMethod("advanceDue", Long.class, LocalDateTime.class, LocalDateTime.class)
                .getAnnotation(Update.class).value());

        assertTrue(dueSql.contains("dispatch_mode = 'SCHEDULED'"));
        assertTrue(dueSql.contains("next_scheduled_at <= #{now}"));
        assertTrue(lockSql.contains("FOR UPDATE"));
        assertTrue(clearSql.contains("account_id = #{accountId}"));
        assertTrue(clearSql.contains("next_scheduled_at = #{expectedScheduledAt}"));
        assertTrue(clearSql.contains("activation_pending = 0"));
        assertTrue(advanceSql.contains("next_scheduled_at = #{expectedScheduledAt}"));
        assertTrue(advanceSql.contains("next_scheduled_at = #{nextScheduledAt}"));
    }

    @Test
    void scanOwnsTheDatabaseTransaction() throws Exception {
        assertTrue(AccountScheduledDispatchService.class
                .getMethod("scan", LocalDateTime.class)
                .isAnnotationPresent(Transactional.class));
    }

    private static AccountScheduledDispatchService service() {
        var service = new AccountScheduledDispatchService();
        service.configMapper = mock(AccountDispatchConfigMapper.class);
        service.accountMapper = mock(AccountMapper.class);
        service.runService = mock(AccountScheduledRunService.class);
        service.calculator = mock(AccountScheduleCalculator.class);
        when(service.configMapper.selectDue(any())).thenReturn(List.of());
        when(service.runService.findDispatchable(any())).thenReturn(List.of());
        return service;
    }

    private static void stubDue(AccountScheduledDispatchService service, LocalDateTime now,
                                AccountDispatchConfigEntity config, AccountEntity account) {
        when(service.configMapper.selectDue(now)).thenReturn(List.of(config));
        when(service.configMapper.selectByIdForUpdate(config.getAccountId())).thenReturn(config);
        when(service.accountMapper.selectById(config.getAccountId())).thenReturn(account);
    }

    private static AccountDispatchConfigEntity dueConfig(Long accountId, LocalDateTime dueAt) {
        return new AccountDispatchConfigEntity()
                .setAccountId(accountId)
                .setDispatchMode(AccountDispatchConfigService.SCHEDULED)
                .setScheduleTime(dueAt.toLocalTime())
                .setNextScheduledAt(dueAt)
                .setActivationPending(0);
    }

    private static AccountEntity validAccount(Long accountId, LocalDateTime now) {
        return new AccountEntity()
                .setId(accountId)
                .setTaskType("daily")
                .setDelete(0)
                .setFreeze(0)
                .setExpireTime(now.plusDays(30));
    }

    private static AccountScheduledRunEntity run(Long id, Long accountId,
                                                  LocalDateTime scheduledFor, String status) {
        return new AccountScheduledRunEntity()
                .setId(id)
                .setAccountId(accountId)
                .setScheduledFor(scheduledFor)
                .setStatus(status)
                .setAttemptCount(0);
    }

    private static String sql(String[] fragments) {
        return String.join(" ", fragments);
    }
}
