package moe.dazecake.inquisition.service.impl;

import moe.dazecake.inquisition.mapper.AccountDispatchConfigMapper;
import moe.dazecake.inquisition.model.entity.AccountDispatchConfigEntity;
import moe.dazecake.inquisition.model.entity.AccountScheduledRunEntity;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
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
        when(service.configMapper.selectDue(now)).thenReturn(List.of(first, second));
        when(service.runService.findDispatchable(now)).thenReturn(List.of(waiting));

        assertEquals(List.of(waiting), service.scan(now));

        var order = inOrder(service.processor);
        order.verify(service.processor).process(7L, now);
        order.verify(service.processor).process(8L, now);
        verify(service.runService).findDispatchable(now);
    }

    @Test
    void oneBrokenConfigurationDoesNotBlockLaterAccounts() {
        var service = service();
        var now = LocalDateTime.of(2026, 7, 28, 20, 0);
        when(service.configMapper.selectDue(now)).thenReturn(List.of(
                dueConfig(7L, now.minusMinutes(30)),
                dueConfig(8L, now.minusMinutes(20)),
                dueConfig(9L, now.minusMinutes(10))));
        doThrow(new IllegalStateException("broken schedule"))
                .when(service.processor).process(8L, now);

        service.scan(now);

        var order = inOrder(service.processor);
        order.verify(service.processor).process(7L, now);
        order.verify(service.processor).process(8L, now);
        order.verify(service.processor).process(9L, now);
        verify(service.runService).findDispatchable(now);
    }

    @Test
    void scanSkipsMalformedDueRows() {
        var service = service();
        var now = LocalDateTime.of(2026, 7, 28, 20, 0);
        when(service.configMapper.selectDue(now)).thenReturn(List.of(
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

    private static AccountScheduledDispatchService service() {
        var service = new AccountScheduledDispatchService();
        service.configMapper = mock(AccountDispatchConfigMapper.class);
        service.runService = mock(AccountScheduledRunService.class);
        service.processor = mock(AccountScheduledDispatchProcessor.class);
        when(service.configMapper.selectDue(any())).thenReturn(List.of());
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
