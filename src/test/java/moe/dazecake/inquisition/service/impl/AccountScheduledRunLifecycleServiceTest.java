package moe.dazecake.inquisition.service.impl;

import moe.dazecake.inquisition.mapper.AccountDispatchConfigMapper;
import moe.dazecake.inquisition.mapper.AccountMapper;
import moe.dazecake.inquisition.model.entity.AccountDispatchConfigEntity;
import moe.dazecake.inquisition.model.entity.AccountEntity;
import moe.dazecake.inquisition.model.entity.TaskAssignmentEntity;
import moe.dazecake.inquisition.model.local.DispatchIntent;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountScheduledRunLifecycleServiceTest {

    @Test
    void startingAScheduledIntentMarksTheSameRunRunning() {
        var service = service();
        var intent = DispatchIntent.scheduled(7L, 41L,
                LocalDateTime.of(2026, 7, 28, 19, 30));
        when(service.runService.markRunning(41L)).thenReturn(true);

        service.start(intent);

        verify(service.runService).markRunning(41L);
    }

    @Test
    void completionSucceedsTheRunWithoutOverwritingTheAlreadyAdvancedPointer() {
        var service = service();
        var finishedAt = LocalDateTime.of(2026, 7, 28, 20, 0);
        var assignment = scheduledAssignment();
        var config = scheduledConfig().setActivationPending(0)
                .setNextScheduledAt(LocalDateTime.of(2026, 7, 29, 8, 0));
        when(service.configMapper.selectByIdForUpdate(7L)).thenReturn(config);
        when(service.runService.succeed(41L)).thenReturn(true);

        assertTrue(service.complete(assignment, finishedAt));

        verify(service.runService).succeed(41L);
        verify(service.configMapper, never()).scheduleNext(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(service.configService, never()).activatePending(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void retryKeepsTheSameRunAndItsBackoff() {
        var service = service();
        var retryAt = LocalDateTime.of(2026, 7, 28, 20, 30);
        when(service.configMapper.selectByIdForUpdate(7L))
                .thenReturn(scheduledConfig().setActivationPending(0));
        when(service.runService.markRetry(41L, "device offline", retryAt)).thenReturn(true);

        assertTrue(service.retry(scheduledAssignment(), "device offline", retryAt));

        verify(service.runService).markRetry(41L, "device offline", retryAt);
        verify(service.runService, never()).cancel(41L);
    }

    @Test
    void pendingModeChangeCancelsTheOldRunAndPreventsOldSourceRequeue() {
        var service = service();
        var closedAt = LocalDateTime.of(2026, 7, 28, 20, 0);
        var account = account();
        when(service.configMapper.selectByIdForUpdate(7L)).thenReturn(
                scheduledConfig().setDispatchMode(AccountDispatchConfigService.AUTO)
                        .setScheduleTime(null).setActivationPending(1));
        when(service.accountMapper.selectById(7L)).thenReturn(account);
        when(service.runService.cancel(41L)).thenReturn(true);

        assertFalse(service.retry(scheduledAssignment(), "network", closedAt));

        verify(service.runService).cancel(41L);
        verify(service.configService).activatePending(account, closedAt);
        verify(service.runService, never()).markRetry(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void nonScheduledAssignmentsDoNotTouchTheScheduledStateMachine() {
        var service = service();
        var assignment = new TaskAssignmentEntity().setAccountId(7L)
                .setDispatchSource(DispatchIntent.SOURCE_MANUAL);

        assertTrue(service.retry(assignment, "network",
                LocalDateTime.of(2026, 7, 28, 20, 30)));

        verify(service.runService, never()).markRetry(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void closingAutoAssignmentActivatesPendingScheduleWhenNoRunExists() {
        var service = service();
        var closedAt = LocalDateTime.of(2026, 7, 28, 20, 0);
        var account = account();
        var assignment = new TaskAssignmentEntity().setAccountId(7L)
                .setDispatchSource(DispatchIntent.SOURCE_AUTO);
        when(service.configMapper.selectByIdForUpdate(7L)).thenReturn(
                scheduledConfig().setActivationPending(1).setNextScheduledAt(null));
        when(service.runService.findActiveByAccount(7L)).thenReturn(java.util.Optional.empty());
        when(service.accountMapper.selectById(7L)).thenReturn(account);

        assertTrue(service.activatePendingIfReady(assignment, closedAt));

        verify(service.configService).activatePending(account, closedAt);
    }

    @Test
    void urgentAssignmentDoesNotActivatePendingChangeAheadOfUnderlyingScheduledRun() {
        var service = service();
        var closedAt = LocalDateTime.of(2026, 7, 28, 20, 0);
        var assignment = new TaskAssignmentEntity().setAccountId(7L)
                .setDispatchSource(DispatchIntent.SOURCE_URGENT_26);
        when(service.configMapper.selectByIdForUpdate(7L)).thenReturn(
                scheduledConfig().setActivationPending(1).setNextScheduledAt(null));
        when(service.runService.findActiveByAccount(7L)).thenReturn(java.util.Optional.of(
                new moe.dazecake.inquisition.model.entity.AccountScheduledRunEntity()
                        .setId(41L).setAccountId(7L)
                        .setStatus(AccountScheduledRunService.STATUS_WAITING)));

        assertFalse(service.activatePendingIfReady(assignment, closedAt));

        verify(service.configService, never()).activatePending(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private static AccountScheduledRunLifecycleService service() {
        var service = new AccountScheduledRunLifecycleService();
        service.runService = mock(AccountScheduledRunService.class);
        service.configMapper = mock(AccountDispatchConfigMapper.class);
        service.configService = mock(AccountDispatchConfigService.class);
        service.accountMapper = mock(AccountMapper.class);
        return service;
    }

    private static TaskAssignmentEntity scheduledAssignment() {
        return new TaskAssignmentEntity().setAccountId(7L)
                .setDispatchSource(DispatchIntent.SOURCE_SCHEDULED)
                .setScheduledRunId(41L);
    }

    private static AccountDispatchConfigEntity scheduledConfig() {
        return new AccountDispatchConfigEntity().setAccountId(7L)
                .setDispatchMode(AccountDispatchConfigService.SCHEDULED)
                .setScheduleTime(LocalTime.of(19, 30));
    }

    private static AccountEntity account() {
        return new AccountEntity().setId(7L).setTaskType("daily")
                .setDelete(0).setFreeze(0)
                .setExpireTime(LocalDateTime.of(2099, 1, 1, 0, 0));
    }
}
