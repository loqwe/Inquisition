package moe.dazecake.inquisition.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import moe.dazecake.inquisition.mapper.AccountDispatchConfigMapper;
import moe.dazecake.inquisition.mapper.AccountScheduledRunMapper;
import moe.dazecake.inquisition.model.entity.AccountDispatchConfigEntity;
import moe.dazecake.inquisition.model.entity.AccountScheduledRunEntity;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
        var service = configuredService();
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
        var service = configuredService();
        var scheduledFor = LocalDateTime.of(2026, 7, 28, 19, 30);
        var existing = run(41L, 7L, scheduledFor, AccountScheduledRunService.STATUS_WAITING);
        when(service.runMapper.selectOne(any())).thenReturn(existing);

        var result = service.createWaiting(7L, scheduledFor);

        assertEquals(existing, result);
        verify(service.runMapper, never()).insert(any());
    }

    @Test
    void aDifferentOccurrenceReusesTheAccountsExistingActiveRun() {
        var service = configuredService();
        var requested = LocalDateTime.of(2026, 7, 29, 19, 30);
        var existing = run(41L, 7L, requested.minusDays(1),
                AccountScheduledRunService.STATUS_RETRY_WAIT);
        when(service.runMapper.selectOne(any())).thenReturn(null, existing);

        var result = service.createWaiting(7L, requested);

        assertEquals(existing, result);
        verify(service.runMapper, never()).insert(any());
    }

    @Test
    void createWaitingLocksTheAccountConfigurationBeforeReadingRuns() {
        var service = configuredService();
        var configMapper = configMapper(service);
        var scheduledFor = LocalDateTime.of(2026, 7, 28, 19, 30);
        var existing = run(41L, 7L, scheduledFor, AccountScheduledRunService.STATUS_WAITING);
        when(service.runMapper.selectOne(any())).thenReturn(existing);

        assertSame(existing, service.createWaiting(7L, scheduledFor));

        var order = inOrder(configMapper, service.runMapper);
        order.verify(configMapper).selectByIdForUpdate(7L);
        order.verify(service.runMapper).selectOne(any());
    }

    @Test
    void createWaitingRejectsAnAccountWithoutAConfigurationRow() {
        var service = service();
        var configMapper = mock(AccountDispatchConfigMapper.class);
        ReflectionTestUtils.setField(service, "configMapper", configMapper);
        when(configMapper.selectByIdForUpdate(7L)).thenReturn(null);

        assertThrows(IllegalStateException.class,
                () -> service.createWaiting(7L, LocalDateTime.of(2026, 7, 28, 19, 30)));

        verify(configMapper).selectByIdForUpdate(7L);
        verifyNoInteractions(service.runMapper);
    }

    @Test
    void duplicateKeyRaceUsesALockingReadToSeeTheConcurrentRun() {
        var service = configuredService();
        var scheduledFor = LocalDateTime.of(2026, 7, 28, 19, 30);
        var concurrent = run(41L, 7L, scheduledFor, AccountScheduledRunService.STATUS_WAITING);
        var duplicateAttempted = new AtomicBoolean();
        when(service.runMapper.selectOne(any())).thenAnswer(invocation -> {
            Wrapper<?> query = invocation.getArgument(0);
            return duplicateAttempted.get()
                    && lastSql(query).contains("FOR UPDATE")
                    ? concurrent
                    : null;
        });
        when(service.runMapper.insert(any())).thenAnswer(invocation -> {
            duplicateAttempted.set(true);
            throw new DuplicateKeyException("slot already created");
        });

        var result = service.createWaiting(7L, scheduledFor);

        assertEquals(concurrent, result);
        verify(service.runMapper, times(3)).selectOne(any());
    }

    @Test
    void concurrentDifferentOccurrencesReuseOneActiveRun() throws Exception {
        var service = configuredService();
        var configMapper = configMapper(service);
        var rowLock = new ReentrantLock();
        var unlockedReaders = new CyclicBarrier(2);
        var persisted = new AtomicReference<AccountScheduledRunEntity>();
        var inserts = new AtomicInteger();
        Map<Long, AtomicInteger> readsByThread = new ConcurrentHashMap<>();
        var config = new AccountDispatchConfigEntity().setAccountId(7L);
        when(configMapper.selectByIdForUpdate(7L)).thenAnswer(invocation -> {
            rowLock.lock();
            return config;
        });
        when(service.runMapper.selectOne(any())).thenAnswer(invocation -> {
            var reads = readsByThread.computeIfAbsent(
                    Thread.currentThread().getId(), ignored -> new AtomicInteger());
            if (reads.incrementAndGet() % 2 == 1) {
                return null;
            }
            var active = persisted.get();
            if (active != null) {
                if (rowLock.isHeldByCurrentThread()) {
                    rowLock.unlock();
                }
                return active;
            }
            if (!rowLock.isHeldByCurrentThread()) {
                unlockedReaders.await(5, TimeUnit.SECONDS);
            }
            return null;
        });
        when(service.runMapper.insert(any())).thenAnswer(invocation -> {
            var created = invocation.<AccountScheduledRunEntity>getArgument(0)
                    .setId(40L + inserts.incrementAndGet());
            persisted.compareAndSet(null, created);
            if (rowLock.isHeldByCurrentThread()) {
                rowLock.unlock();
            }
            return 1;
        });
        var executor = Executors.newFixedThreadPool(2, runnable -> {
            var thread = new Thread(runnable, "scheduled-run-race-test");
            thread.setDaemon(true);
            return thread;
        });

        try {
            var first = executor.submit(() -> service.createWaiting(
                    7L, LocalDateTime.of(2026, 7, 28, 19, 30)));
            var second = executor.submit(() -> service.createWaiting(
                    7L, LocalDateTime.of(2026, 7, 29, 19, 30)));

            var firstResult = first.get(5, TimeUnit.SECONDS);
            var secondResult = second.get(5, TimeUnit.SECONDS);
            assertEquals(1, inserts.get());
            assertSame(firstResult, secondResult);
        } finally {
            executor.shutdownNow();
        }
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
    void dispatchableRunsComeFromTheBoundedDatabaseQuery() {
        var service = service();
        service.batchSize = 2;
        var now = LocalDateTime.of(2026, 7, 28, 20, 0);
        var waiting = run(1L, 1L, now.minusMinutes(30), AccountScheduledRunService.STATUS_WAITING);
        var dueRetry = run(2L, 2L, now.minusMinutes(20), AccountScheduledRunService.STATUS_RETRY_WAIT)
                .setNextRetryAt(now);
        when(service.runMapper.selectDispatchable(now, 2)).thenReturn(List.of(waiting, dueRetry));

        var result = service.findDispatchable(now);

        assertEquals(List.of(waiting, dueRetry), result);
        verify(service.runMapper).selectDispatchable(now, 2);
        verify(service.runMapper, never()).selectList(any());
    }

    @Test
    void dispatchableQueryFiltersOrdersAndLimitsInSql() throws Exception {
        var method = AccountScheduledRunMapper.class
                .getMethod("selectDispatchable", LocalDateTime.class, int.class);
        var sql = String.join(" ", method.getAnnotation(Select.class).value());

        assertTrue(sql.contains("status = 'WAITING'"));
        assertTrue(sql.contains("status = 'RETRY_WAIT' AND next_retry_at <= #{now}"));
        assertTrue(sql.contains("ORDER BY scheduled_for, id"));
        assertTrue(sql.contains("LIMIT #{limit}"));
    }

    @Test
    void dispatchableQueryRejectsANonPositiveBatchSize() {
        var service = service();
        service.batchSize = 0;

        assertThrows(IllegalStateException.class,
                () -> service.findDispatchable(LocalDateTime.of(2026, 7, 28, 20, 0)));

        verifyNoInteractions(service.runMapper);
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
    void markWaitingRejectsRunningWithoutWriting() {
        var service = service();
        var run = run(41L, 7L, LocalDateTime.of(2026, 7, 28, 19, 30),
                AccountScheduledRunService.STATUS_RUNNING).setAttemptCount(3);
        when(service.runMapper.selectById(41L)).thenReturn(run);

        assertFalse(service.markWaiting(41L));

        assertEquals(AccountScheduledRunService.STATUS_RUNNING, run.getStatus());
        assertEquals(3, run.getAttemptCount());
        assertNull(run.getNextRetryAt());
        verify(service.runMapper, never()).update(any(), any());
    }

    @Test
    void markWaitingIsIdempotentForWaitingWithoutWriting() {
        var service = service();
        var run = run(41L, 7L, LocalDateTime.of(2026, 7, 28, 19, 30),
                AccountScheduledRunService.STATUS_WAITING);
        when(service.runMapper.selectById(41L)).thenReturn(run);

        assertTrue(service.markWaiting(41L));

        verify(service.runMapper, never()).update(any(), any());
    }

    @Test
    void markWaitingReleasesRetryWait() {
        var service = service();
        var run = run(41L, 7L, LocalDateTime.of(2026, 7, 28, 19, 30),
                AccountScheduledRunService.STATUS_RETRY_WAIT)
                .setNextRetryAt(LocalDateTime.of(2026, 7, 28, 20, 30))
                .setLastError("offline");
        when(service.runMapper.selectById(41L)).thenReturn(run);
        when(service.runMapper.update(any(), any())).thenReturn(1);

        assertTrue(service.markWaiting(41L));

        assertEquals(AccountScheduledRunService.STATUS_WAITING, run.getStatus());
        assertNull(run.getNextRetryAt());
        assertNull(run.getLastError());
        verify(service.runMapper).update(any(), any());
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
        var service = configuredService();
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

    private static AccountScheduledRunService configuredService() {
        var service = service();
        var configMapper = mock(AccountDispatchConfigMapper.class);
        ReflectionTestUtils.setField(service, "configMapper", configMapper);
        when(configMapper.selectByIdForUpdate(any())).thenAnswer(invocation ->
                new AccountDispatchConfigEntity().setAccountId(invocation.getArgument(0)));
        return service;
    }

    private static AccountDispatchConfigMapper configMapper(AccountScheduledRunService service) {
        return (AccountDispatchConfigMapper) ReflectionTestUtils.getField(service, "configMapper");
    }

    private static String lastSql(Wrapper<?> query) {
        var lastSql = ReflectionTestUtils.getField(query, "lastSql");
        var value = (String) ReflectionTestUtils.invokeMethod(lastSql, "getStringValue");
        return value == null ? "" : value;
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
