package moe.dazecake.inquisition.service.impl;

import moe.dazecake.inquisition.mapper.AccountDispatchConfigMapper;
import moe.dazecake.inquisition.mapper.AccountMapper;
import moe.dazecake.inquisition.model.entity.AccountDispatchConfigEntity;
import moe.dazecake.inquisition.model.entity.AccountEntity;
import moe.dazecake.inquisition.model.entity.AccountScheduledRunEntity;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountScheduledDispatchProcessorTest {

    @Test
    void sameGameDayDueCreatesOneWaitingRunAndAdvancesToTheNextConfiguredTime() {
        var processor = processor();
        var now = LocalDateTime.of(2026, 7, 28, 20, 0);
        var dueAt = now.minusMinutes(30);
        var next = LocalDateTime.of(2026, 7, 29, 8, 0);
        var times = List.of(LocalTime.of(8, 0), LocalTime.of(14, 0), LocalTime.of(19, 30));
        var config = dueConfig(7L, dueAt);
        var account = validAccount(7L, now);
        var created = run(41L, 7L, dueAt);
        stubLocked(processor, config, account);
        when(processor.runService.findActiveByAccount(7L)).thenReturn(Optional.empty());
        when(processor.calculator.belongsToCurrentGameDay(dueAt, now)).thenReturn(true);
        when(processor.runService.createWaiting(7L, dueAt)).thenReturn(created);
        when(processor.configService.getScheduleTimes(config)).thenReturn(times);
        when(processor.calculator.nextOccurrence(account, times, dueAt)).thenReturn(next);
        when(processor.configMapper.advanceDue(7L, dueAt, next)).thenReturn(1);

        processor.process(7L, now);

        verify(processor.configMapper).selectByIdForUpdate(7L);
        verify(processor.runService).createWaiting(7L, dueAt);
        verify(processor.configMapper).advanceDue(7L, dueAt, next);
    }

    @Test
    void oldGameDayDueAdvancesWithoutCreatingARun() {
        var processor = processor();
        var now = LocalDateTime.of(2026, 7, 29, 4, 5);
        var dueAt = LocalDateTime.of(2026, 7, 28, 19, 30);
        var next = LocalDateTime.of(2026, 7, 29, 19, 30);
        var config = dueConfig(7L, dueAt);
        var account = validAccount(7L, now);
        var times = List.of(LocalTime.of(8, 0), LocalTime.of(19, 30));
        stubLocked(processor, config, account);
        when(processor.runService.findActiveByAccount(7L)).thenReturn(Optional.empty());
        when(processor.configService.getScheduleTimes(config)).thenReturn(times);
        when(processor.calculator.nextOccurrence(account, times, now)).thenReturn(next);
        when(processor.configMapper.advanceDue(7L, dueAt, next)).thenReturn(1);

        processor.process(7L, now);

        verify(processor.runService, never()).createWaiting(any(), any());
        verify(processor.configMapper).advanceDue(7L, dueAt, next);
    }

    @Test
    void activeRunPreservesTheOverduePointerForLaterProcessing() {
        var processor = processor();
        var now = LocalDateTime.of(2026, 7, 29, 4, 5);
        var dueAt = LocalDateTime.of(2026, 7, 28, 19, 30);
        var config = dueConfig(7L, dueAt);
        stubLocked(processor, config, validAccount(7L, now));
        when(processor.runService.findActiveByAccount(7L))
                .thenReturn(Optional.of(run(41L, 7L, dueAt)));

        processor.process(7L, now);

        verify(processor.runService, never()).createWaiting(any(), any());
        verify(processor.configMapper, never()).clearDue(any(), any());
        verify(processor.configMapper, never()).advanceDue(any(), any(), any());
    }

    @Test
    void malformedScheduleRollsBackItsAccountTransaction() {
        var processor = processor();
        var now = LocalDateTime.of(2026, 7, 28, 20, 0);
        var config = dueConfig(7L, now.minusMinutes(30)).setScheduleTime(null);
        stubLocked(processor, config, validAccount(7L, now).setFreeze(1));

        assertThrows(IllegalStateException.class, () -> processor.process(7L, now));
    }

    @Test
    void staleNarrowUpdateFailsTheAccountTransaction() {
        var processor = processor();
        var now = LocalDateTime.of(2026, 7, 28, 20, 0);
        var config = dueConfig(7L, now.minusMinutes(30));
        stubLocked(processor, config, validAccount(7L, now).setDelete(1));
        when(processor.configMapper.clearDue(7L, config.getNextScheduledAt())).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> processor.process(7L, now));
    }

    @Test
    void processUsesRequiresNewTransaction() throws Exception {
        var annotation = AccountScheduledDispatchProcessor.class
                .getMethod("process", Long.class, LocalDateTime.class)
                .getAnnotation(Transactional.class);

        assertEquals(Propagation.REQUIRES_NEW, annotation.propagation());
    }

    private static AccountScheduledDispatchProcessor processor() {
        var processor = new AccountScheduledDispatchProcessor();
        processor.configMapper = mock(AccountDispatchConfigMapper.class);
        processor.accountMapper = mock(AccountMapper.class);
        processor.runService = mock(AccountScheduledRunService.class);
        processor.configService = mock(AccountDispatchConfigService.class);
        processor.calculator = mock(AccountScheduleCalculator.class);
        return processor;
    }

    private static void stubLocked(AccountScheduledDispatchProcessor processor,
                                   AccountDispatchConfigEntity config, AccountEntity account) {
        when(processor.configMapper.selectByIdForUpdate(config.getAccountId())).thenReturn(config);
        when(processor.accountMapper.selectById(config.getAccountId())).thenReturn(account);
    }

    private static AccountDispatchConfigEntity dueConfig(Long accountId, LocalDateTime dueAt) {
        return new AccountDispatchConfigEntity()
                .setAccountId(accountId)
                .setDispatchMode(AccountDispatchConfigService.SCHEDULED)
                .setScheduleTime(LocalTime.of(19, 30))
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

    private static AccountScheduledRunEntity run(Long id, Long accountId, LocalDateTime scheduledFor) {
        return new AccountScheduledRunEntity()
                .setId(id)
                .setAccountId(accountId)
                .setScheduledFor(scheduledFor)
                .setStatus(AccountScheduledRunService.STATUS_WAITING);
    }
}
