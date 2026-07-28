package moe.dazecake.inquisition.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import moe.dazecake.inquisition.mapper.DashboardMetricsMapper;
import moe.dazecake.inquisition.model.entity.DeviceEntity;
import moe.dazecake.inquisition.model.vo.account.AccountWithSanVO;
import moe.dazecake.inquisition.model.vo.dashboard.DashboardAccountMetrics;
import moe.dazecake.inquisition.model.vo.dashboard.DashboardBusinessMetrics;
import moe.dazecake.inquisition.model.vo.device.DeviceRuntimeProjection;
import moe.dazecake.inquisition.model.vo.query.PageQueryVO;
import moe.dazecake.inquisition.model.vo.task.RunningTaskVO;
import moe.dazecake.inquisition.model.vo.task.ScheduledTaskOverviewVO;
import moe.dazecake.inquisition.model.vo.task.ScheduledTaskStatusVO;
import moe.dazecake.inquisition.model.vo.task.TaskBoardAccountVO;
import moe.dazecake.inquisition.model.vo.task.TaskBoardSummaryVO;
import moe.dazecake.inquisition.model.vo.task.TaskBoardVO;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminDashboardOverviewServiceTest {

    @Test
    void overviewUsesOneShanghaiSnapshotAndReturnsOnlySanitizedOperationalData() throws Exception {
        var service = new AdminDashboardOverviewService();
        service.dashboardMetricsMapper = mock(DashboardMetricsMapper.class);
        service.accountService = mock(AccountServiceImpl.class);
        service.taskBoardService = mock(TaskBoardService.class);
        service.deviceRuntimeProjectionService = mock(DeviceRuntimeProjectionService.class);
        service.scheduledTaskMonitorService = mock(ScheduledTaskMonitorService.class);
        var now = LocalDateTime.of(2026, 7, 29, 14, 0);
        when(service.dashboardMetricsMapper.selectAccountMetrics(any(), any(), any(), any()))
                .thenReturn(new DashboardAccountMetrics().setEligibleDaily(63L).setMissingLogin(2L)
                        .setFrozen(1L).setExpiringWithinSevenDays(3L)
                        .setNewAccountsToday(4L).setValidAccounts(64L));
        when(service.dashboardMetricsMapper.selectBusinessMetrics(any(), any(), any(), any()))
                .thenReturn(new DashboardBusinessMetrics().setDayIncome(12.5).setMonthIncome(88.0));
        var missingPage = new PageQueryVO<AccountWithSanVO>();
        missingPage.setTotal(2L);
        missingPage.setRecords(List.of(missingAccount(7L, "账号7"), missingAccount(8L, "账号8")));
        when(service.accountService.queryAllAccount(1L, 5L, null, null, null, null,
                "missing", now)).thenReturn(missingPage);
        var running = new RunningTaskVO().setAssignmentId("assignment-1").setAccountId(9L)
                .setName("账号9").setTaskMode("NORMAL").setDispatchSource("AUTO")
                .setDeviceName("设备A").setDeviceToken("full-device-token")
                .setAssignedAt(now.minusMinutes(130)).setRunningMinutes(130L)
                .setLastProgressTitle("基建换班").setUrgent(false);
        var board = new TaskBoardVO().setGeneratedAt(now)
                .setSummary(new TaskBoardSummaryVO().setUrgent(0).setPending(1)
                        .setInProgress(1).setCoolingDown(0).setFrozen(0))
                .setPendingTasks(List.of(new TaskBoardAccountVO().setId(10L).setName("账号10")
                        .setDispatchSource("SCHEDULED")))
                .setRunningTasks(List.of(running));
        when(service.taskBoardService.getReadOnlySnapshot(now)).thenReturn(board);
        when(service.deviceRuntimeProjectionService.project(now, board.getRunningTasks())).thenReturn(List.of(
                device(1L, "设备A", "token-a", DeviceRuntimeProjectionService.BUSY, now),
                device(2L, "设备B", "token-b", DeviceRuntimeProjectionService.IDLE, now),
                device(3L, "设备C", "token-c", DeviceRuntimeProjectionService.SUSPENDED, now)));
        var failedTask = new ScheduledTaskStatusVO("daily-login", "每日补登", "", "", "Asia/Shanghai",
                "", "FAILED", true, "FAILED", "CRON", now.minusHours(1), now.minusMinutes(59),
                now.minusDays(1), now.minusMinutes(59), now.plusHours(1), 1000L, 1, 10,
                "fixture failure", now.minusMinutes(59));
        when(service.scheduledTaskMonitorService.getOverview(now)).thenReturn(
                new ScheduledTaskOverviewVO(now, 1, 0, 0, 1, 0, 0, List.of(failedTask)));

        var overview = service.getOverview(now);

        assertEquals("2026-07-29T14:00:00+08:00", overview.getGeneratedAt());
        assertEquals("Asia/Shanghai", overview.getTimeZone());
        assertEquals(61L, overview.getAccounts().getLoggedToday());
        assertEquals(2L, overview.getAccounts().getMissingLogin());
        assertEquals(1, overview.getTasks().getLongRunning());
        assertEquals("WARNING", overview.getOverallStatus());
        assertEquals(3, overview.getDevices().getOnline());
        assertTrue(overview.getDevices().getItems().stream()
                .allMatch(item -> item.getTokenSuffix().length() <= 4));
        assertTrue(overview.getTasks().getRunningItems().stream()
                .noneMatch(item -> "full-device-token".equals(item.getDeviceName())));
        var json = new ObjectMapper().findAndRegisterModules().writeValueAsString(overview);
        assertFalse(json.contains("deviceToken"));
        assertFalse(json.contains("password"));
        assertFalse(json.contains("pushPlusToken"));
        assertFalse(json.contains("full-device-token"));
    }

    @Test
    void missingLoginBecomesCriticalAtTwentySixOClock() {
        var service = new AdminDashboardOverviewService();
        service.dashboardMetricsMapper = mock(DashboardMetricsMapper.class);
        service.accountService = mock(AccountServiceImpl.class);
        service.taskBoardService = mock(TaskBoardService.class);
        service.deviceRuntimeProjectionService = mock(DeviceRuntimeProjectionService.class);
        service.scheduledTaskMonitorService = mock(ScheduledTaskMonitorService.class);
        var now = LocalDateTime.of(2026, 7, 30, 2, 0);
        when(service.dashboardMetricsMapper.selectAccountMetrics(any(), any(), any(), any()))
                .thenReturn(new DashboardAccountMetrics().setEligibleDaily(1L).setMissingLogin(1L));
        when(service.dashboardMetricsMapper.selectBusinessMetrics(any(), any(), any(), any()))
                .thenReturn(new DashboardBusinessMetrics());
        var missingPage = new PageQueryVO<AccountWithSanVO>();
        missingPage.setTotal(1L);
        missingPage.setRecords(List.of(missingAccount(7L, "账号7")));
        when(service.accountService.queryAllAccount(1L, 5L, null, null, null, null,
                "missing", now)).thenReturn(missingPage);
        var board = new TaskBoardVO();
        when(service.taskBoardService.getReadOnlySnapshot(now)).thenReturn(board);
        when(service.deviceRuntimeProjectionService.project(now, board.getRunningTasks()))
                .thenReturn(List.of());
        when(service.scheduledTaskMonitorService.getOverview(now)).thenReturn(
                new ScheduledTaskOverviewVO(now, 0, 0, 0, 0, 0, 0, List.of()));

        var overview = service.getOverview(now);

        assertEquals("2026-07-29", overview.getGameDay());
        assertEquals("CRITICAL", overview.getOverallStatus());
        assertEquals(1, overview.getAlertCount());
    }

    private static AccountWithSanVO missingAccount(Long id, String name) {
        var account = new AccountWithSanVO();
        account.setId(id);
        account.setName(name);
        account.setDispatchMode("AUTO");
        return account;
    }

    private static DeviceRuntimeProjection device(Long id, String name, String token, String state,
                                                   LocalDateTime now) {
        return new DeviceRuntimeProjection()
                .setDevice(new DeviceEntity().setId(id).setDeviceName(name).setDeviceToken(token).setDelete(0))
                .setRuntimeState(state).setLastHeartbeatAt(now.minusMinutes(1));
    }
}
