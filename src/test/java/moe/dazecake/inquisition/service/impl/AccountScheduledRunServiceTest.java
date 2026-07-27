package moe.dazecake.inquisition.service.impl;

import moe.dazecake.inquisition.mapper.AccountScheduledRunMapper;
import moe.dazecake.inquisition.model.entity.AccountScheduledRunEntity;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountScheduledRunServiceTest {

    @Test
    void onlyWaitingRunningAndRetryWaitAreActive() {
        assertTrue(AccountScheduledRunService.isActiveStatus(AccountScheduledRunService.STATUS_WAITING));
        assertTrue(AccountScheduledRunService.isActiveStatus(AccountScheduledRunService.STATUS_RUNNING));
        assertTrue(AccountScheduledRunService.isActiveStatus(AccountScheduledRunService.STATUS_RETRY_WAIT));
        assertFalse(AccountScheduledRunService.isActiveStatus(AccountScheduledRunService.STATUS_SUCCEEDED));
        assertFalse(AccountScheduledRunService.isActiveStatus(AccountScheduledRunService.STATUS_CANCELLED));
        assertFalse(AccountScheduledRunService.isActiveStatus(AccountScheduledRunService.STATUS_FAILED));
    }

    @Test
    void createsOneWaitingRunForTheScheduledOccurrence() {
        var service = service();
        var scheduledFor = LocalDateTime.of(2026, 7, 28, 3, 30);
        when(service.runMapper.selectOne(any())).thenReturn(null).thenReturn(null);
        when(service.runMapper.insert(any())).thenAnswer(invocation -> {
            invocation.<AccountScheduledRunEntity>getArgument(0).setId(41L);
            return 1;
        });

        var run = service.createWaiting(7L, scheduledFor);

        assertEquals(41L, run.getId());
        assertEquals(7L, run.getAccountId());
        assertEquals(scheduledFor, run.getScheduledFor());
        assertEquals(LocalDate.of(2026, 7, 27), run.getGameDay());
        assertEquals(AccountScheduledRunService.STATUS_WAITING, run.getStatus());
        assertEquals(0, run.getAttemptCount());
        assertNull(run.getCreatedAt());
        assertNull(run.getUpdatedAt());
        verify(service.runMapper).insert(run);
    }

    @Test
    void repeatedCreationForTheSameOccurrenceReturnsTheExistingRun() {
        var service = service();
        var scheduledFor = LocalDateTime.of(2026, 7, 28, 19, 30);
        var existing = run(41L, 7L, scheduledFor, AccountScheduledRunService.STATUS_WAITING);
        when(service.runMapper.selectOne(any())).thenReturn(existing);

        var result = service.createWaiting(7L, scheduledFor);

        assertEquals(existing, result);
        verify(service.runMapper, never()).insert(any());
    }

    @Test
    void aDifferentOccurrenceReusesTheAccountsExistingActiveRun() {
        var service = service();
        var requested = LocalDateTime.of(2026, 7, 29, 19, 30);
        var existing = run(41L, 7L, requested.minusDays(1),
                AccountScheduledRunService.STATUS_RETRY_WAIT);
        when(service.runMapper.selectOne(any())).thenReturn(null, existing);

        var result = service.createWaiting(7L, requested);

        assertEquals(existing, result);
        verify(service.runMapper, never()).insert(any());
    }

    @Test
    void duplicateKeyRaceReturnsTheRunCreatedByTheOtherTransaction() {
        var service = service();
        var scheduledFor = LocalDateTime.of(2026, 7, 28, 19, 30);
        var concurrent = run(41L, 7L, scheduledFor, AccountScheduledRunService.STATUS_WAITING);
        when(service.runMapper.selectOne(any()))
                .thenReturn(null)
                .thenReturn(null)
                .thenReturn(concurrent);
        when(service.runMapper.insert(any())).thenThrow(new DuplicateKeyException("slot already created"));

        var result = service.createWaiting(7L, scheduledFor);

        assertEquals(concurrent, result);
    }

    @Test
    void findsRunsByIdAndActiveAccount() {
        var service = service();
        var active = run(41L, 7L, LocalDateTime.of(2026, 7, 28, 19, 30),
                AccountScheduledRunService.STATUS_RUNNING);
        when(service.runMapper.selectById(41L)).thenReturn(active);
        when(service.runMapper.selectOne(any())).thenReturn(active);

        assertEquals(active, service.findById(41L).orElseThrow());
        assertEquals(active, service.findActiveByAccount(7L).orElseThrow());
    }

    @Test
    void dispatchableRunsIncludeWaitingAndOnlyDueRetries() {
        var service = service();
        var now = LocalDateTime.of(2026, 7, 28, 20, 0);
        var waiting = run(1L, 1L, now.minusMinutes(30), AccountScheduledRunService.STATUS_WAITING);
        var dueRetry = run(2L, 2L, now.minusMinutes(20), AccountScheduledRunService.STATUS_RETRY_WAIT)
                .setNextRetryAt(now);
        var futureRetry = run(3L, 3L, now.minusMinutes(10), AccountScheduledRunService.STATUS_RETRY_WAIT)
                .setNextRetryAt(now.plusMinutes(1));
        var running = run(4L, 4L, now.minusMinutes(40), AccountScheduledRunService.STATUS_RUNNING);
        var succeeded = run(5L, 5L, now.minusMinutes(50), AccountScheduledRunService.STATUS_SUCCEEDED);
        when(service.runMapper.selectList(any())).thenReturn(
                List.of(futureRetry, succeeded, dueRetry, running, waiting));

        var result = service.findDispatchable(now);

        assertEquals(List.of(1L, 2L), result.stream()
                .map(AccountScheduledRunEntity::getId)
                .collect(Collectors.toList()));
    }

    @Test
    void markRunningIncrementsAttemptCount() {
        var service = service();
        var run = run(41L, 7L, LocalDateTime.of(2026, 7, 28, 19, 30),
                AccountScheduledRunService.STATUS_WAITING).setAttemptCount(2);
        when(service.runMapper.selectById(41L)).thenReturn(run);
        when(service.runMapper.update(any(), any())).thenReturn(1);

        assertTrue(service.markRunning(41L));

        assertEquals(AccountScheduledRunService.STATUS_RUNNING, run.getStatus());
        assertEquals(3, run.getAttemptCount());
        verify(service.runMapper).update(any(), any());
    }

    @Test
    void markRetryRecordsBoundedErrorAndNextAttempt() {
        var service = service();
        var run = run(41L, 7L, LocalDateTime.of(2026, 7, 28, 19, 30),
                AccountScheduledRunService.STATUS_RUNNING);
        var retryAt = LocalDateTime.of(2026, 7, 28, 20, 10);
        when(service.runMapper.selectById(41L)).thenReturn(run);
        when(service.runMapper.update(any(), any())).thenReturn(1);

        assertTrue(service.markRetry(41L, "x".repeat(300), retryAt));

        assertEquals(AccountScheduledRunService.STATUS_RETRY_WAIT, run.getStatus());
        assertEquals(retryAt, run.getNextRetryAt());
        assertEquals(255, run.getLastError().length());
    }

    @Test
    void markWaitingRequeuesRunningWithoutResettingAttempts() {
        var service = service();
        var run = run(41L, 7L, LocalDateTime.of(2026, 7, 28, 19, 30),
                AccountScheduledRunService.STATUS_RUNNING).setAttemptCount(3);
        when(service.runMapper.selectById(41L)).thenReturn(run);
        when(service.runMapper.update(any(), any())).thenReturn(1);

        assertTrue(service.markWaiting(41L));

        assertEquals(AccountScheduledRunService.STATUS_WAITING, run.getStatus());
        assertEquals(3, run.getAttemptCount());
        assertNull(run.getNextRetryAt());
    }

    @Test
    void retryNowOnlyReleasesRetryWait() {
        var service = service();
        var retrying = run(41L, 7L, LocalDateTime.of(2026, 7, 28, 19, 30),
                AccountScheduledRunService.STATUS_RETRY_WAIT)
                .setNextRetryAt(LocalDateTime.of(2026, 7, 28, 21, 0))
                .setLastError("network");
        when(service.runMapper.selectById(41L)).thenReturn(retrying);
        when(service.runMapper.update(any(), any())).thenReturn(1);

        assertTrue(service.retryNow(41L));
        assertEquals(AccountScheduledRunService.STATUS_WAITING, retrying.getStatus());
        assertNull(retrying.getNextRetryAt());
        assertNull(retrying.getLastError());

        var running = run(42L, 8L, retrying.getScheduledFor(), AccountScheduledRunService.STATUS_RUNNING);
        when(service.runMapper.selectById(42L)).thenReturn(running);
        assertFalse(service.retryNow(42L));
    }

    @Test
    void succeedMakesRunningRunTerminal() {
        var service = service();
        var run = run(41L, 7L, LocalDateTime.of(2026, 7, 28, 19, 30),
                AccountScheduledRunService.STATUS_RUNNING);
        when(service.runMapper.selectById(41L)).thenReturn(run);
        when(service.runMapper.update(any(), any())).thenReturn(1);

        assertTrue(service.succeed(41L));

        assertEquals(AccountScheduledRunService.STATUS_SUCCEEDED, run.getStatus());
        assertFalse(AccountScheduledRunService.isActiveStatus(run.getStatus()));
    }

    @Test
    void cancelMakesAnyActiveRunTerminal() {
        var service = service();
        var run = run(41L, 7L, LocalDateTime.of(2026, 7, 28, 19, 30),
                AccountScheduledRunService.STATUS_RETRY_WAIT);
        when(service.runMapper.selectById(41L)).thenReturn(run);
        when(service.runMapper.update(any(), any())).thenReturn(1);

        assertTrue(service.cancel(41L));

        assertEquals(AccountScheduledRunService.STATUS_CANCELLED, run.getStatus());
        assertFalse(AccountScheduledRunService.isActiveStatus(run.getStatus()));
    }

    @Test
    void failMakesAnyActiveRunTerminalAndKeepsTheError() {
        var service = service();
        var run = run(41L, 7L, LocalDateTime.of(2026, 7, 28, 19, 30),
                AccountScheduledRunService.STATUS_WAITING);
        when(service.runMapper.selectById(41L)).thenReturn(run);
        when(service.runMapper.update(any(), any())).thenReturn(1);

        assertTrue(service.fail(41L, "invalid config"));

        assertEquals(AccountScheduledRunService.STATUS_FAILED, run.getStatus());
        assertEquals("invalid config", run.getLastError());
        assertFalse(AccountScheduledRunService.isActiveStatus(run.getStatus()));
    }

    @Test
    void everyAttemptedWriteRequiresExactlyOneAffectedRow() {
        var service = service();
        var scheduledFor = LocalDateTime.of(2026, 7, 28, 19, 30);
        when(service.runMapper.selectOne(any())).thenReturn(null).thenReturn(null);
        when(service.runMapper.insert(any())).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> service.createWaiting(7L, scheduledFor));

        var run = run(41L, 7L, scheduledFor, AccountScheduledRunService.STATUS_WAITING);
        when(service.runMapper.selectById(41L)).thenReturn(run);
        when(service.runMapper.update(any(), any())).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> service.markRunning(41L));
    }

    private static AccountScheduledRunService service() {
        var service = new AccountScheduledRunService();
        service.runMapper = mock(AccountScheduledRunMapper.class);
        return service;
    }

    private static AccountScheduledRunEntity run(Long id, Long accountId,
                                                  LocalDateTime scheduledFor, String status) {
        return new AccountScheduledRunEntity()
                .setId(id)
                .setAccountId(accountId)
                .setScheduledFor(scheduledFor)
                .setGameDay(scheduledFor.toLocalTime().isBefore(java.time.LocalTime.of(4, 0))
                        ? scheduledFor.toLocalDate().minusDays(1)
                        : scheduledFor.toLocalDate())
                .setStatus(status)
                .setAttemptCount(0);
    }
}
