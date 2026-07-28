package moe.dazecake.inquisition.service.impl;

import moe.dazecake.inquisition.mapper.AccountMapper;
import moe.dazecake.inquisition.model.entity.AccountEntity;
import moe.dazecake.inquisition.model.entity.AccountScheduledRunEntity;
import moe.dazecake.inquisition.model.entity.TaskAssignmentEntity;
import moe.dazecake.inquisition.model.entity.UrgentTaskEntity;
import moe.dazecake.inquisition.model.local.DispatchIntent;
import moe.dazecake.inquisition.utils.DynamicInfo;
import moe.dazecake.inquisition.utils.GameDayClock;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DispatchQueueServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 28, 20, 0);

    @Test
    void ordersUrgentThenHighTierFifoThenOriginalAutoOrder() {
        var service = service();
        var firstScheduled = scheduledRun(41L, 4L, NOW.minusHours(2),
                AccountScheduledRunService.STATUS_WAITING);
        var secondScheduled = scheduledRun(42L, 5L, NOW.minusHours(1),
                AccountScheduledRunService.STATUS_WAITING);
        when(service.urgentTaskService.findActiveByAccount(6L, GameDayClock.gameDay(NOW)))
                .thenReturn(Optional.of(urgentTask(61L, 6L, NOW.minusMinutes(2))));
        when(service.urgentTaskService.findActiveByAccount(7L, GameDayClock.gameDay(NOW)))
                .thenReturn(Optional.of(urgentTask(62L, 7L, NOW.minusMinutes(1))));

        assertTrue(service.enqueueAuto(1L));
        assertTrue(service.enqueueAuto(2L));
        assertTrue(service.enqueueManual(3L));
        assertTrue(service.enqueueScheduled(firstScheduled));
        assertTrue(service.enqueueScheduled(secondScheduled));
        assertTrue(service.enqueueUrgent(6L));
        assertTrue(service.enqueueUrgent(7L));

        assertEquals(List.of(6L, 7L, 3L, 4L, 5L, 1L, 2L),
                service.dynamicInfo.getWaitUserList());
    }

    @Test
    void duplicateAdmissionDoesNotMoveAutoAccountsOrDuplicateIds() {
        var service = service();

        service.enqueueAuto(1L);
        service.enqueueAuto(2L);

        assertFalse(service.enqueueAuto(1L));
        assertEquals(List.of(1L, 2L), service.dynamicInfo.getWaitUserList());
    }

    @Test
    void rejectsLegacyAutoAdmissionForAScheduledAccount() {
        var service = service();
        when(service.configService.isAuto(9L)).thenReturn(false);

        assertFalse(service.enqueueAuto(9L));
        assertNull(service.resolve(9L, NOW));
        assertTrue(service.dynamicInfo.getWaitUserList().isEmpty());
    }

    @Test
    void resolvesAnExistingScheduledRunWithoutRecheckingItsCurrentWeekday() {
        var service = service();
        var run = scheduledRun(41L, 9L, NOW.minusDays(3),
                AccountScheduledRunService.STATUS_WAITING);
        when(service.configService.isAuto(9L)).thenReturn(false);
        when(service.runService.findActiveByAccount(9L)).thenReturn(Optional.of(run));

        assertTrue(service.enqueueScheduled(run));

        var resolved = service.resolve(9L, NOW);
        assertEquals(DispatchIntent.SOURCE_SCHEDULED, resolved.getSource());
        assertEquals(41L, resolved.getScheduledRunId());
    }

    @Test
    void manualAdmissionReusesAndWakesAnActiveScheduledRun() {
        var service = service();
        var run = scheduledRun(41L, 9L, NOW.minusHours(3),
                AccountScheduledRunService.STATUS_RETRY_WAIT)
                .setNextRetryAt(NOW.plusHours(1));
        when(service.configService.isAuto(9L)).thenReturn(false);
        when(service.runService.findActiveByAccount(9L)).thenReturn(Optional.of(run));
        when(service.runService.retryNow(41L)).thenReturn(true);

        assertTrue(service.enqueueManual(9L));

        verify(service.runService).retryNow(41L);
        assertEquals(DispatchIntent.SOURCE_SCHEDULED,
                service.resolve(9L, NOW).getSource());
        assertEquals(List.of(9L), service.dynamicInfo.getWaitUserList());
    }

    @Test
    void restorationKeepsOneQueueIdForSimultaneousUrgentAndScheduledIntents() {
        var service = service();
        var run = scheduledRun(41L, 9L, NOW.minusDays(1),
                AccountScheduledRunService.STATUS_WAITING);
        var urgent = urgentTask(61L, 9L, NOW.minusMinutes(5));
        service.dynamicInfo.setWaitUserList(new ArrayList<>(List.of(1L, 9L, 2L, 1L)));
        when(service.configService.isAuto(9L)).thenReturn(false);
        when(service.runService.findDispatchable(NOW)).thenReturn(List.of(run));
        when(service.runService.findActiveByAccount(9L)).thenReturn(Optional.of(run));
        when(service.urgentTaskService.findDispatchable(GameDayClock.gameDay(NOW), NOW))
                .thenReturn(List.of(urgent));
        when(service.urgentTaskService.findActiveByAccount(9L, GameDayClock.gameDay(NOW)))
                .thenReturn(Optional.of(urgent));

        service.reconcileRestoredQueue(NOW);

        assertEquals(List.of(9L, 1L, 2L), service.dynamicInfo.getWaitUserList());
        assertEquals(DispatchIntent.SOURCE_URGENT_26,
                service.resolve(9L, NOW).getSource());
    }

    @Test
    void requeuePreservesThePersistedAssignmentSource() {
        var service = service();
        var run = scheduledRun(41L, 9L, NOW.minusHours(1),
                AccountScheduledRunService.STATUS_WAITING);
        when(service.configService.isAuto(9L)).thenReturn(false);
        when(service.runService.findById(41L)).thenReturn(Optional.of(run));
        when(service.runService.findActiveByAccount(9L)).thenReturn(Optional.of(run));
        var assignment = new TaskAssignmentEntity()
                .setAccountId(9L)
                .setDispatchSource(DispatchIntent.SOURCE_SCHEDULED)
                .setScheduledRunId(41L);

        service.requeue(assignment);

        var resolved = service.resolve(9L, NOW);
        assertEquals(DispatchIntent.SOURCE_SCHEDULED, resolved.getSource());
        assertEquals(41L, resolved.getScheduledRunId());
    }

    @Test
    void fourteenOClockPromotionOnlyMovesAutoAccountsBehindTheHighTier() {
        var service = service();
        var run = scheduledRun(41L, 8L, NOW.minusHours(1),
                AccountScheduledRunService.STATUS_WAITING);
        var urgent = urgentTask(61L, 7L, NOW.minusMinutes(5));
        when(service.configService.isAuto(1L)).thenReturn(false);
        when(service.urgentTaskService.findActiveByAccount(7L, GameDayClock.gameDay(NOW)))
                .thenReturn(Optional.of(urgent));

        service.enqueueScheduled(run);
        service.enqueueUrgent(7L, NOW);
        service.enqueueAuto(99L);
        service.enqueueAuto(3L);
        service.enqueueAuto(5L);

        assertEquals(List.of(3L), service.promoteAutos(List.of(1L, 7L, 3L), NOW));

        assertEquals(List.of(7L, 8L, 3L, 99L, 5L), service.dynamicInfo.getWaitUserList());
    }

    @Test
    void forceReloadRebuildsOnlyAutoWhilePreservingEveryHighTierIntent() {
        var service = service();
        var run = scheduledRun(41L, 8L, NOW.minusHours(1),
                AccountScheduledRunService.STATUS_WAITING);
        when(service.configService.isAuto(11L)).thenReturn(false);

        service.enqueueManual(7L);
        service.enqueueScheduled(run);
        service.enqueueAuto(1L);
        service.enqueueAuto(2L);

        service.replaceAutos(List.of(10L, 11L), NOW);

        assertEquals(List.of(7L, 8L, 10L), service.dynamicInfo.getWaitUserList());
    }

    @Test
    void urgentConsumptionCanRestoreTheUnderlyingScheduledIntent() {
        var service = service();
        var run = scheduledRun(41L, 9L, NOW.minusHours(1),
                AccountScheduledRunService.STATUS_WAITING);
        var urgent = urgentTask(61L, 9L, NOW.minusMinutes(5));
        var activeUrgent = new AtomicReference<>(Optional.of(urgent));
        when(service.configService.isAuto(9L)).thenReturn(false);
        when(service.runService.findActiveByAccount(9L)).thenReturn(Optional.of(run));
        when(service.urgentTaskService.findActiveByAccount(9L, GameDayClock.gameDay(NOW)))
                .thenAnswer(invocation -> activeUrgent.get());

        service.enqueueScheduled(run);
        service.enqueueUrgent(9L, NOW);
        service.remove(9L);
        activeUrgent.set(Optional.empty());
        service.restoreBest(9L, NOW);

        assertEquals(List.of(9L), service.dynamicInfo.getWaitUserList());
        assertEquals(DispatchIntent.SOURCE_SCHEDULED,
                service.resolve(9L, NOW).getSource());
    }

    @Test
    void completedUrgentAssignmentRequeuesItsUnderlyingScheduledRun() {
        var service = service();
        var run = scheduledRun(41L, 9L, NOW.minusHours(1),
                AccountScheduledRunService.STATUS_WAITING);
        when(service.configService.isAuto(9L)).thenReturn(false);
        when(service.runService.findActiveByAccount(9L)).thenReturn(Optional.of(run));
        var assignment = new TaskAssignmentEntity().setAccountId(9L)
                .setUrgentTaskId(61L)
                .setDispatchSource(DispatchIntent.SOURCE_URGENT_26);

        service.requeue(assignment);

        assertEquals(List.of(9L), service.dynamicInfo.getWaitUserList());
        assertEquals(DispatchIntent.SOURCE_SCHEDULED,
                service.resolve(9L, NOW).getSource());
    }

    private static DispatchQueueService service() {
        var service = new DispatchQueueService();
        service.dynamicInfo = new DynamicInfo();
        service.accountMapper = mock(AccountMapper.class);
        service.configService = mock(AccountDispatchConfigService.class);
        service.runService = mock(AccountScheduledRunService.class);
        service.urgentTaskService = mock(UrgentTaskService.class);
        when(service.accountMapper.selectById(anyLong())).thenAnswer(invocation -> validAccount(
                invocation.getArgument(0)));
        when(service.configService.isAuto(anyLong())).thenReturn(true);
        when(service.runService.findActiveByAccount(anyLong())).thenReturn(Optional.empty());
        when(service.runService.findDispatchable(any())).thenReturn(List.of());
        when(service.urgentTaskService.findActiveByAccount(anyLong(), any()))
                .thenReturn(Optional.empty());
        when(service.urgentTaskService.findDispatchable(any(), any())).thenReturn(List.of());
        return service;
    }

    private static AccountEntity validAccount(Long accountId) {
        return new AccountEntity()
                .setId(accountId)
                .setTaskType("daily")
                .setDelete(0)
                .setFreeze(0)
                .setExpireTime(LocalDateTime.of(2099, 1, 1, 0, 0));
    }

    private static AccountScheduledRunEntity scheduledRun(Long id, Long accountId,
                                                           LocalDateTime scheduledFor, String status) {
        return new AccountScheduledRunEntity()
                .setId(id)
                .setAccountId(accountId)
                .setScheduledFor(scheduledFor)
                .setStatus(status);
    }

    private static UrgentTaskEntity urgentTask(Long id, Long accountId, LocalDateTime createdAt) {
        return new UrgentTaskEntity()
                .setId(id)
                .setAccountId(accountId)
                .setGameDay(GameDayClock.gameDay(NOW))
                .setStatus(UrgentTaskService.STATUS_WAITING)
                .setCreatedAt(createdAt);
    }
}
