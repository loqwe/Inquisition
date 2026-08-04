package moe.dazecake.inquisition.utils;

import moe.dazecake.inquisition.service.impl.DailyLoginSweepService;
import moe.dazecake.inquisition.service.impl.FinalLoginSweepService;
import moe.dazecake.inquisition.service.impl.AccountScheduledDispatchService;
import moe.dazecake.inquisition.service.impl.AccountScheduledRunService;
import moe.dazecake.inquisition.service.impl.DispatchQueueService;
import moe.dazecake.inquisition.service.impl.PartialScheduledDispatchException;
import moe.dazecake.inquisition.service.impl.ScheduledTaskMonitorService;
import moe.dazecake.inquisition.service.impl.TaskAssignmentService;
import moe.dazecake.inquisition.service.impl.UrgentTaskService;
import moe.dazecake.inquisition.model.entity.TaskAssignmentEntity;
import moe.dazecake.inquisition.model.entity.UrgentTaskEntity;
import moe.dazecake.inquisition.model.entity.AccountScheduledRunEntity;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunScriptTest {

    @Test
    void startupRestoresAndScansScheduledRunsThroughTheUnifiedQueue() {
        var script = new RunScript();
        script.dynamicInfo = new DynamicInfo();
        script.enableAccountSchedule = true;
        script.accountScheduledDispatchService = mock(AccountScheduledDispatchService.class);
        script.dispatchQueueService = mock(DispatchQueueService.class);
        script.scheduledTaskMonitor = mock(ScheduledTaskMonitorService.class);
        var now = LocalDateTime.of(2026, 7, 28, 4, 5);
        var waiting = scheduledRun(41L, 7L, now.minusDays(1),
                AccountScheduledRunService.STATUS_WAITING);
        var retry = scheduledRun(42L, 8L, now.minusDays(2),
                AccountScheduledRunService.STATUS_RETRY_WAIT);
        when(script.accountScheduledDispatchService.restoreDispatchable(now)).thenReturn(List.of(waiting));
        when(script.accountScheduledDispatchService.scan(now)).thenReturn(List.of(waiting, retry));
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(2)).run();
            return null;
        }).when(script.scheduledTaskMonitor).execute(
                eq(DynamicScheduleTask.ACCOUNT_SCHEDULED_DISPATCH_TASK),
                eq("STARTUP_RECOVERY"), any(Runnable.class));

        script.runAccountScheduledDispatchCatchUp(now);

        verify(script.accountScheduledDispatchService).restoreDispatchable(now);
        verify(script.scheduledTaskMonitor).execute(
                eq(DynamicScheduleTask.ACCOUNT_SCHEDULED_DISPATCH_TASK),
                eq("STARTUP_RECOVERY"), any(Runnable.class));
        verify(script.accountScheduledDispatchService).scan(now);
        verify(script.dispatchQueueService).enqueueScheduledRuns(List.of(waiting), now);
        verify(script.dispatchQueueService).enqueueScheduledRuns(List.of(waiting, retry), now);
    }

    @Test
    void disabledStartupDoesNotRestoreOrScanScheduledRuns() {
        var script = new RunScript();
        script.accountScheduledDispatchService = mock(AccountScheduledDispatchService.class);
        script.scheduledTaskMonitor = mock(ScheduledTaskMonitorService.class);

        script.runAccountScheduledDispatchCatchUp(LocalDateTime.of(2026, 7, 28, 4, 5));

        verify(script.accountScheduledDispatchService, never()).restoreDispatchable(any());
        verify(script.accountScheduledDispatchService, never()).scan(any());
        verify(script.scheduledTaskMonitor, never()).execute(
                eq(DynamicScheduleTask.ACCOUNT_SCHEDULED_DISPATCH_TASK),
                eq("STARTUP_RECOVERY"), any(Runnable.class));
    }

    @Test
    void startupAdmitsPartialScanResultsBeforeTheMonitorSeesTheFailure() {
        var script = new RunScript();
        script.enableAccountSchedule = true;
        script.accountScheduledDispatchService = mock(AccountScheduledDispatchService.class);
        script.dispatchQueueService = mock(DispatchQueueService.class);
        script.scheduledTaskMonitor = mock(ScheduledTaskMonitorService.class);
        var now = LocalDateTime.of(2026, 7, 28, 20, 0);
        var waiting = scheduledRun(41L, 7L, now.minusMinutes(30),
                AccountScheduledRunService.STATUS_WAITING);
        when(script.accountScheduledDispatchService.restoreDispatchable(now)).thenReturn(List.of());
        when(script.accountScheduledDispatchService.scan(now))
                .thenThrow(new PartialScheduledDispatchException(1, List.of(waiting)));
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(2)).run();
            return null;
        }).when(script.scheduledTaskMonitor).execute(
                eq(DynamicScheduleTask.ACCOUNT_SCHEDULED_DISPATCH_TASK),
                eq("STARTUP_RECOVERY"), any(Runnable.class));

        assertThrows(PartialScheduledDispatchException.class,
                () -> script.runAccountScheduledDispatchCatchUp(now));

        verify(script.dispatchQueueService).enqueueScheduledRuns(List.of(waiting), now);
    }

    @Test
    void dailyLoginCatchUpUsesTheExistingScheduledTaskKey() {
        var script = new RunScript();
        script.dailyLoginSweepService = mock(DailyLoginSweepService.class);
        script.scheduledTaskMonitor = mock(ScheduledTaskMonitorService.class);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(2)).run();
            return null;
        }).when(script.scheduledTaskMonitor).execute(eq(DynamicScheduleTask.DAILY_LOGIN_SWEEP_TASK),
                eq("STARTUP_RECOVERY"), any(Runnable.class));
        var now = LocalDateTime.of(2026, 7, 27, 15, 0);

        script.runDailyLoginCatchUp(now);

        verify(script.scheduledTaskMonitor).execute(eq(DynamicScheduleTask.DAILY_LOGIN_SWEEP_TASK),
                eq("STARTUP_RECOVERY"), any(Runnable.class));
        verify(script.dailyLoginSweepService).runIfDue(now);
    }

    @Test
    void finalLoginCatchUpUsesTheFinalSweepTaskKey() {
        var script = new RunScript();
        script.finalLoginSweepService = mock(FinalLoginSweepService.class);
        script.scheduledTaskMonitor = mock(ScheduledTaskMonitorService.class);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(2)).run();
            return null;
        }).when(script.scheduledTaskMonitor).execute(eq(DynamicScheduleTask.FINAL_LOGIN_SWEEP_TASK),
                eq("STARTUP_RECOVERY"), any(Runnable.class));
        var now = LocalDateTime.of(2026, 7, 28, 2, 30);

        script.runFinalLoginCatchUp(now);

        verify(script.finalLoginSweepService).runIfDue(now);
    }

    @Test
    void startupAlwaysRunsGameDayAwareUrgentCleanup() {
        var script = new RunScript();
        script.finalLoginSweepService = mock(FinalLoginSweepService.class);
        var now = LocalDateTime.of(2026, 7, 28, 5, 0);
        when(script.finalLoginSweepService.cleanup(now)).thenReturn(3);

        assertEquals(3, script.cleanupUrgentLoginTasks(now));

        verify(script.finalLoginSweepService).cleanup(now);
    }

    @Test
    void restoresOnlyTwentySixUrgencyAndRepairsStaleRunningRowsAfterRestart() {
        var script = new RunScript();
        script.dynamicInfo = new DynamicInfo();
        script.urgentTaskService = mock(UrgentTaskService.class);
        script.taskAssignmentService = mock(TaskAssignmentService.class);
        script.dispatchQueueService = mock(DispatchQueueService.class);
        var now = LocalDateTime.of(2026, 7, 28, 2, 30);
        var gameDay = LocalDate.of(2026, 7, 27);
        var waiting = urgent(1L, 1L, UrgentTaskService.STATUS_WAITING, null);
        var staleRunning = urgent(2L, 2L, UrgentTaskService.STATUS_RUNNING, null);
        var activeRunning = urgent(3L, 3L, UrgentTaskService.STATUS_RUNNING, null);
        var retryAt = now.plusMinutes(20);
        var retryWaiting = urgent(4L, 4L, UrgentTaskService.STATUS_RETRY_WAIT, retryAt);
        when(script.urgentTaskService.findActiveForGameDay(gameDay))
                .thenReturn(List.of(waiting, staleRunning, activeRunning, retryWaiting));
        when(script.taskAssignmentService.findByAccount(2L)).thenReturn(Optional.empty());
        when(script.taskAssignmentService.findByAccount(3L)).thenReturn(Optional.of(
                new TaskAssignmentEntity().setAccountId(3L).setAssignmentId("active")));
        when(script.urgentTaskService.markWaiting(2L, now)).thenReturn(true);

        assertEquals(3, script.restoreUrgentLoginTasks(now));

        assertEquals(retryAt, script.dynamicInfo.getFreezeUserInfoMap().get(4L));
        verify(script.dispatchQueueService).restoreBest(1L, now);
        verify(script.dispatchQueueService).restoreBest(2L, now);
        verify(script.dispatchQueueService).restoreBest(4L, now);
        verify(script.urgentTaskService).markWaiting(2L, now);
    }

    private static UrgentTaskEntity urgent(Long id, Long accountId, String status, LocalDateTime nextRetryAt) {
        return new UrgentTaskEntity().setId(id).setAccountId(accountId)
                .setGameDay(LocalDate.of(2026, 7, 27))
                .setStatus(status).setNextRetryAt(nextRetryAt);
    }

    private static AccountScheduledRunEntity scheduledRun(Long id, Long accountId,
                                                           LocalDateTime scheduledFor, String status) {
        return new AccountScheduledRunEntity()
                .setId(id)
                .setAccountId(accountId)
                .setScheduledFor(scheduledFor)
                .setStatus(status);
    }
}
