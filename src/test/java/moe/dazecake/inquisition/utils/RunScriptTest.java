package moe.dazecake.inquisition.utils;

import moe.dazecake.inquisition.service.impl.DailyLoginSweepService;
import moe.dazecake.inquisition.service.impl.ScheduledTaskMonitorService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RunScriptTest {

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
}
