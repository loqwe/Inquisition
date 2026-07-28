package moe.dazecake.inquisition.service.impl;

import moe.dazecake.inquisition.mapper.AccountDispatchConfigMapper;
import moe.dazecake.inquisition.model.entity.AccountDispatchConfigEntity;
import moe.dazecake.inquisition.model.entity.AccountScheduledRunEntity;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountScheduledDispatchServiceTest {

    @Test
    void scanDelegatesEveryDueAccountAndReturnsDispatchableRuns() {
        var service = service();
        var now = LocalDateTime.of(2026, 7, 28, 20, 0);
        var first = dueConfig(7L, now.minusMinutes(30));
        var second = dueConfig(8L, now.minusMinutes(20));
        var waiting = run(41L, 7L, first.getNextScheduledAt());
        when(service.configMapper.selectDue(now, AccountScheduledDispatchService.DEFAULT_BATCH_SIZE))
                .thenReturn(List.of(first, second));
        when(service.runService.findDispatchable(now)).thenReturn(List.of(waiting));

        assertEquals(List.of(waiting), service.scan(now));

        var order = inOrder(service.processor);
        order.verify(service.processor).process(7L, now);
        order.verify(service.processor).process(8L, now);
        verify(service.runService).findDispatchable(now);
    }

    @Test
    void partialFailureRestoresSuccessfulAndExistingDispatchableRunsBeforeThrowing() {
        var service = service();
        service.batchSize = 2;
        var now = LocalDateTime.of(2026, 7, 28, 20, 0);
        var successful = run(41L, 7L, now.minusMinutes(30));
        var existingWaiting = run(42L, 9L, now.minusDays(1));
        var dueRetry = run(43L, 10L, now.minusDays(2))
                .setStatus(AccountScheduledRunService.STATUS_RETRY_WAIT)
                .setNextRetryAt(now);
        when(service.configMapper.selectDue(now, 2)).thenReturn(List.of(
                dueConfig(7L, now.minusMinutes(30)),
                dueConfig(8L, now.minusMinutes(20))));
        doThrow(new IllegalStateException("broken schedule"))
                .when(service.processor).process(8L, now);
        when(service.runService.findDispatchable(now))
                .thenReturn(List.of(successful, existingWaiting, dueRetry));

        var exception = assertThrows(PartialScheduledDispatchException.class,
                () -> service.scan(now));

        var order = inOrder(service.processor, service.runService);
        order.verify(service.processor).process(7L, now);
        order.verify(service.processor).process(8L, now);
        order.verify(service.runService).findDispatchable(now);
        assertEquals(1, exception.getFailureCount());
        assertEquals(List.of(successful, existingWaiting, dueRetry),
                exception.getDispatchableRuns());
        assertEquals("Scheduled account dispatch completed with 1 failure(s)",
                exception.getMessage());
        assertFalse(exception.getMessage().toLowerCase().contains("password"));
        assertFalse(exception.getMessage().toLowerCase().contains("token"));
    }

    @Test
    void partialFailureExposesAnImmutableDispatchableSnapshot() {
        var service = service();
        var now = LocalDateTime.of(2026, 7, 28, 20, 0);
        var waiting = run(41L, 7L, now.minusMinutes(30));
        var source = new ArrayList<>(List.of(waiting));
        when(service.configMapper.selectDue(now, AccountScheduledDispatchService.DEFAULT_BATCH_SIZE))
                .thenReturn(List.of(dueConfig(7L, now.minusMinutes(30))));
        doThrow(new IllegalStateException("broken schedule"))
                .when(service.processor).process(7L, now);
        when(service.runService.findDispatchable(now)).thenReturn(source);

        var exception = assertThrows(PartialScheduledDispatchException.class,
                () -> service.scan(now));
        source.clear();

        assertEquals(List.of(waiting), exception.getDispatchableRuns());
        assertThrows(UnsupportedOperationException.class,
                () -> exception.getDispatchableRuns().add(run(42L, 8L, now)));
    }

    @Test
    void scanProcessesAtMostTheConfiguredBatchSize() {
        var service = service();
        service.batchSize = 2;
        var now = LocalDateTime.of(2026, 7, 28, 20, 0);
        when(service.configMapper.selectDue(now, 2)).thenReturn(List.of(
                dueConfig(7L, now.minusMinutes(30)),
                dueConfig(8L, now.minusMinutes(20)),
                dueConfig(9L, now.minusMinutes(10))));

        service.scan(now);

        verify(service.processor).process(7L, now);
        verify(service.processor).process(8L, now);
        verify(service.processor, never()).process(9L, now);
    }

    @Test
    void scanSkipsMalformedDueRows() {
        var service = service();
        var now = LocalDateTime.of(2026, 7, 28, 20, 0);
        when(service.configMapper.selectDue(now, AccountScheduledDispatchService.DEFAULT_BATCH_SIZE))
                .thenReturn(List.of(
                new AccountDispatchConfigEntity(), dueConfig(7L, now.minusMinutes(30))));

        service.scan(now);

        verify(service.processor).process(7L, now);
        verify(service.processor, never()).process(null, now);
    }

    @Test
    void restoreReturnsThePersistedDispatchableRuns() {
        var service = service();
        var now = LocalDateTime.of(2026, 7, 28, 20, 0);
        var waiting = run(41L, 7L, now.minusDays(2));
        when(service.runService.findDispatchable(now)).thenReturn(List.of(waiting));

        assertEquals(List.of(waiting), service.restoreDispatchable(now));
    }

    @Test
    void scanCoordinatorDoesNotOwnTheDatabaseTransaction() throws Exception {
        assertFalse(AccountScheduledDispatchService.class
                .getMethod("scan", LocalDateTime.class)
                .isAnnotationPresent(Transactional.class));
    }

    @Test
    void dueQueryIsOrderedAndBoundedInTheDatabase() throws Exception {
        var method = AccountDispatchConfigMapper.class
                .getMethod("selectDue", LocalDateTime.class, int.class);
        var sql = String.join(" ", method.getAnnotation(Select.class).value());

        assertTrue(sql.contains("next_scheduled_at <= #{now}"));
        assertTrue(sql.contains("ORDER BY next_scheduled_at, account_id"));
        assertTrue(sql.contains("LIMIT #{limit}"));
    }

    @Test
    void scanRejectsANonPositiveBatchSizeBeforeQuerying() {
        var service = service();
        service.batchSize = 0;

        assertThrows(IllegalStateException.class,
                () -> service.scan(LocalDateTime.of(2026, 7, 28, 20, 0)));

        verify(service.configMapper, never()).selectDue(any(), anyInt());
    }

    private static AccountScheduledDispatchService service() {
        var service = new AccountScheduledDispatchService();
        service.configMapper = mock(AccountDispatchConfigMapper.class);
        service.runService = mock(AccountScheduledRunService.class);
        service.processor = mock(AccountScheduledDispatchProcessor.class);
        when(service.configMapper.selectDue(any(), anyInt())).thenReturn(List.of());
        when(service.runService.findDispatchable(any())).thenReturn(List.of());
        return service;
    }

    private static AccountDispatchConfigEntity dueConfig(Long accountId, LocalDateTime dueAt) {
        return new AccountDispatchConfigEntity()
                .setAccountId(accountId)
                .setDispatchMode(AccountDispatchConfigService.SCHEDULED)
                .setNextScheduledAt(dueAt);
    }

    private static AccountScheduledRunEntity run(Long id, Long accountId, LocalDateTime scheduledFor) {
        return new AccountScheduledRunEntity()
                .setId(id)
                .setAccountId(accountId)
                .setScheduledFor(scheduledFor)
                .setStatus(AccountScheduledRunService.STATUS_WAITING);
    }
}
