package moe.dazecake.inquisition.utils;

import moe.dazecake.inquisition.mapper.AccountMapper;
import moe.dazecake.inquisition.model.entity.AccountEntity;
import moe.dazecake.inquisition.model.entity.AdminEntity;
import moe.dazecake.inquisition.model.entity.DeviceEntity;
import moe.dazecake.inquisition.model.entity.TaskAssignmentEntity;
import moe.dazecake.inquisition.service.impl.AccountRuntimeService;
import moe.dazecake.inquisition.service.impl.ChinacServiceImpl;
import moe.dazecake.inquisition.service.impl.DeviceRuntimeService;
import moe.dazecake.inquisition.service.impl.LogServiceImpl;
import moe.dazecake.inquisition.service.impl.MessageServiceImpl;
import moe.dazecake.inquisition.service.impl.TaskAssignmentService;
import moe.dazecake.inquisition.service.impl.TaskServiceImpl;
import moe.dazecake.inquisition.mapper.AdminMapper;
import moe.dazecake.inquisition.mapper.DeviceMapper;
import moe.dazecake.inquisition.mapper.LogMapper;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.SimpleTriggerContext;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DynamicScheduleTaskTest {

    @Test
    void dailyLoginSweepRunsAtFourteenInShanghai() {
        var scheduler = new DynamicScheduleTask();
        var registrar = new ScheduledTaskRegistrar();
        scheduler.configureTasks(registrar);
        var lastRun = Date.from(Instant.parse("2026-07-27T05:00:00Z"));
        var context = new SimpleTriggerContext(lastRun, lastRun, lastRun);
        var tasks = registrar.getTriggerTaskList();

        var nextRun = tasks.get(tasks.size() - 1).getTrigger().nextExecutionTime(context);

        assertEquals(Instant.parse("2026-07-27T06:00:00Z"), nextRun.toInstant());
    }

    @Test
    void deviceOfflineScanRunsEveryFiveMinutes() {
        var scheduler = new DynamicScheduleTask();
        var registrar = new ScheduledTaskRegistrar();
        scheduler.configureTasks(registrar);
        var lastRun = Date.from(Instant.parse("2026-07-21T04:00:00Z"));
        var context = new SimpleTriggerContext(lastRun, lastRun, lastRun);

        var nextRun = registrar.getTriggerTaskList().get(2).getTrigger().nextExecutionTime(context);

        assertEquals(5 * 60 * 1000L, nextRun.getTime() - lastRun.getTime());
    }

    @Test
    void twoHourTaskReminderNotifiesUserAndAdminOnlyOnce() {
        var scheduler = new DynamicScheduleTask();
        scheduler.accountMapper = mock(AccountMapper.class);
        scheduler.taskAssignmentService = mock(TaskAssignmentService.class);
        scheduler.messageService = mock(MessageServiceImpl.class);
        scheduler.deviceMapper = mock(DeviceMapper.class);
        scheduler.adminMapper = mock(AdminMapper.class);
        scheduler.logMapper = mock(LogMapper.class);
        scheduler.logService = mock(LogServiceImpl.class);
        scheduler.taskService = mock(TaskServiceImpl.class);
        scheduler.deviceRuntimeService = mock(DeviceRuntimeService.class);
        scheduler.accountRuntimeService = mock(AccountRuntimeService.class);
        scheduler.chinacService = mock(ChinacServiceImpl.class);

        var now = LocalDateTime.of(2026, 7, 19, 13, 0);
        var assignment = new TaskAssignmentEntity()
                .setAssignmentId("assignment-long")
                .setAccountId(398L)
                .setDeviceToken("device-1")
                .setAssignedAt(now.minusMinutes(125));
        var account = new AccountEntity().setId(398L).setName("账号774");
        when(scheduler.taskAssignmentService.findLongRunning(now, 120)).thenReturn(List.of(assignment));
        when(scheduler.taskAssignmentService.markLongTaskNotified(assignment)).thenReturn(true);
        when(scheduler.accountMapper.selectById(398L)).thenReturn(account);

        scheduler.notifyLongRunningTasks(now);

        verify(scheduler.messageService).push(eq(account), eq("任务运行时间较长"), contains("已达到2小时上限"));
        verify(scheduler.messageService).pushAdmin(contains("任务占用过久"), contains("设备 device-1"));
        verify(scheduler.taskAssignmentService).markLongTaskNotified(assignment);
    }

    @Test
    void missingLogScanRunsOffTheSchedulerAndDoesNotQueueOverlappingRuns() {
        var scheduler = new DynamicScheduleTask();
        scheduler.accountRuntimeService = mock(AccountRuntimeService.class);
        var queued = new ArrayList<Runnable>();
        scheduler.missingLogExecutor = queued::add;
        var firstRun = LocalDateTime.of(2026, 7, 19, 13, 10);

        scheduler.submitMissingLogCheck(firstRun);
        scheduler.submitMissingLogCheck(firstRun.plusHours(1));

        assertEquals(1, queued.size());
        verify(scheduler.accountRuntimeService, never()).checkMissingLogs(any());

        queued.get(0).run();
        verify(scheduler.accountRuntimeService).checkMissingLogs(firstRun);

        scheduler.submitMissingLogCheck(firstRun.plusHours(1));
        assertEquals(2, queued.size());
    }

    @Test
    void adminSummaryOnlyIncludesRecentlyOfflineImportantDevices() {
        var scheduler = new DynamicScheduleTask();
        scheduler.accountMapper = mock(AccountMapper.class);
        scheduler.deviceMapper = mock(DeviceMapper.class);
        scheduler.logMapper = mock(LogMapper.class);
        scheduler.logService = mock(LogServiceImpl.class);
        scheduler.messageService = mock(MessageServiceImpl.class);
        scheduler.dynamicInfo = new DynamicInfo();
        var now = GameDayClock.now().withSecond(0).withNano(0);
        var important = new DeviceEntity().setDeviceName("A").setDeviceToken("a").setDelete(0);
        var regular = new DeviceEntity().setDeviceName("B").setDeviceToken("b").setDelete(0);
        when(scheduler.deviceMapper.selectList(any())).thenReturn(List.of(important, regular));
        when(scheduler.accountMapper.selectList(any())).thenReturn(List.of());
        when(scheduler.logMapper.selectList(any())).thenReturn(List.of());
        scheduler.dynamicInfo.getDeviceStatusMap().put("a", 0);
        scheduler.dynamicInfo.getDeviceStatusMap().put("b", 0);
        scheduler.dynamicInfo.getDeviceLastHeartbeatMap().put("a", now.minusMinutes(10));
        scheduler.dynamicInfo.getDeviceLastHeartbeatMap().put("b", now.minusMinutes(10));
        var admins = new ArrayList<AdminEntity>();
        admins.add(new AdminEntity());

        scheduler.sendAdminSummaryNow(admins);

        verify(scheduler.messageService).pushAdmin(eq(admins), eq("[审判庭] 设备1台"),
                org.mockito.ArgumentMatchers.argThat(content -> content.contains("设备 A")
                        && !content.contains("设备 B")));
    }
}
