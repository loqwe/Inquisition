package moe.dazecake.inquisition.service.impl;

import moe.dazecake.inquisition.mapper.AccountMapper;
import moe.dazecake.inquisition.mapper.LogMapper;
import moe.dazecake.inquisition.model.entity.AccountEntity;
import moe.dazecake.inquisition.model.entity.TaskAssignmentEntity;
import moe.dazecake.inquisition.model.entity.UrgentTaskEntity;
import moe.dazecake.inquisition.utils.DynamicInfo;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FinalLoginSweepServiceTest {

    @Test
    void twentySixOClockIsNextDayTwoAndUpgradesEveryStillMissingAccount() {
        var service = service();
        var now = LocalDateTime.of(2026, 7, 28, 2, 0);
        var gameDay = LocalDate.of(2026, 7, 27);
        when(service.logMapper.selectCount(any())).thenReturn(0L);
        when(service.accountMapper.selectList(any())).thenReturn(List.of(
                account(1L, now.plusDays(1)),
                account(2L, now.plusDays(1)),
                account(3L, now.plusDays(1)),
                account(4L, now.plusDays(1))
        ));
        when(service.dailyLoginService.getLoginCounts(any(), eq(now))).thenReturn(Map.of(2L, 1));
        when(service.taskAssignmentService.findAll()).thenReturn(List.of(
                new TaskAssignmentEntity().setAccountId(3L).setAssignmentId("running")));
        service.dynamicInfo.getFreezeUserInfoMap().put(4L, now.plusMinutes(20));
        service.dynamicInfo.setWaitUserList(new java.util.ArrayList<>(List.of(99L, 1L, 4L)));

        var result = service.runIfDue(now);

        assertTrue(result.isExecuted());
        assertEquals(gameDay, result.getGameDay());
        assertEquals(3, result.getMissingCount());
        assertEquals(1, result.getRunningCount());
        assertEquals(1, result.getCooldownCount());
        assertEquals(List.of(1L, 4L, 99L), service.dynamicInfo.getWaitUserList());
        verify(service.urgentTaskService).upsert(eq(1L), eq(gameDay),
                eq(UrgentTaskService.TRIGGER_TWENTY_SIX), eq(UrgentTaskService.MODE_LOGIN_ONLY),
                eq(UrgentTaskService.PRIORITY_TWENTY_SIX), eq(UrgentTaskService.STATUS_WAITING),
                eq(null), eq(now));
        verify(service.urgentTaskService).upsert(eq(3L), eq(gameDay),
                eq(UrgentTaskService.TRIGGER_TWENTY_SIX), eq(UrgentTaskService.MODE_LOGIN_ONLY),
                eq(UrgentTaskService.PRIORITY_TWENTY_SIX), eq(UrgentTaskService.STATUS_RUNNING),
                eq(null), eq(now));
        verify(service.urgentTaskService).upsert(eq(4L), eq(gameDay),
                eq(UrgentTaskService.TRIGGER_TWENTY_SIX), eq(UrgentTaskService.MODE_LOGIN_ONLY),
                eq(UrgentTaskService.PRIORITY_TWENTY_SIX), eq(UrgentTaskService.STATUS_RETRY_WAIT),
                eq(now.plusMinutes(20)), eq(now));
        verify(service.messageService).pushAdmin(contains("26点最终补登"), contains("3"));
    }

    @Test
    void skipsBeforeTwoAndUsesThePersistentMarkerAfterRestart() {
        var before = service();
        assertFalse(before.runIfDue(LocalDateTime.of(2026, 7, 28, 1, 59)).isExecuted());
        verify(before.logMapper, never()).selectCount(any());

        var restarted = service();
        when(restarted.logMapper.selectCount(any())).thenReturn(1L);
        assertFalse(restarted.runIfDue(LocalDateTime.of(2026, 7, 28, 2, 30)).isExecuted());
        verify(restarted.accountMapper, never()).selectList(any());
    }

    @Test
    void failureSummaryReportsActiveRowsAndCleanupUsesTheNewGameDay() {
        var service = service();
        var now = LocalDateTime.of(2026, 7, 28, 3, 45);
        when(service.urgentTaskService.findActiveForGameDay(LocalDate.of(2026, 7, 27))).thenReturn(List.of(
                new UrgentTaskEntity().setAccountId(1L).setStatus(UrgentTaskService.STATUS_WAITING),
                new UrgentTaskEntity().setAccountId(2L).setStatus(UrgentTaskService.STATUS_RETRY_WAIT)
                        .setLastError("network")));
        when(service.accountMapper.selectBatchIds(any())).thenReturn(List.of(
                account(1L, now.plusDays(1)).setName("账号1"),
                account(2L, now.plusDays(1)).setName("账号2")));
        var lingeringAssignment = new TaskAssignmentEntity()
                .setAssignmentId("assignment-login-only")
                .setAccountId(1L)
                .setDeviceToken("device-1")
                .setTaskMode(UrgentTaskService.MODE_LOGIN_ONLY)
                .setUrgentTaskId(11L);
        when(service.taskAssignmentService.findAll()).thenReturn(List.of(lingeringAssignment));
        when(service.urgentTaskService.findById(11L)).thenReturn(java.util.Optional.of(
                new UrgentTaskEntity().setId(11L).setGameDay(LocalDate.of(2026, 7, 27))
                        .setStatus(UrgentTaskService.STATUS_RUNNING)));
        when(service.taskAssignmentService.closeAssignment(
                lingeringAssignment, "EXPIRED_GAME_DAY", "twenty-six login window ended", true))
                .thenReturn(true);
        when(service.urgentTaskService.cleanupBefore(LocalDate.of(2026, 7, 28))).thenReturn(5);

        assertEquals(2, service.sendFailureSummary(now));
        verify(service.messageService).pushAdmin(contains("26点补登失败"), contains("账号1"));
        assertEquals(5, service.cleanup(LocalDateTime.of(2026, 7, 28, 4, 0)));
        assertTrue(service.dynamicInfo.getHaltList().contains("device-1"));
        verify(service.taskAssignmentService).closeAssignment(
                lingeringAssignment, "EXPIRED_GAME_DAY", "twenty-six login window ended", true);
        verify(service.urgentTaskService).cleanupBefore(LocalDate.of(2026, 7, 28));
    }

    @Test
    void startupCleanupBeforeFourPreservesTheCurrentGameDaysLoginOnlyAssignment() {
        var service = service();
        var now = LocalDateTime.of(2026, 7, 28, 2, 30);
        var assignment = new TaskAssignmentEntity()
                .setAssignmentId("assignment-current-login")
                .setAccountId(1L)
                .setDeviceToken("device-1")
                .setTaskMode(UrgentTaskService.MODE_LOGIN_ONLY)
                .setUrgentTaskId(11L);
        when(service.taskAssignmentService.findAll()).thenReturn(List.of(assignment));
        when(service.urgentTaskService.findById(11L)).thenReturn(java.util.Optional.of(
                new UrgentTaskEntity().setId(11L).setGameDay(LocalDate.of(2026, 7, 27))
                        .setStatus(UrgentTaskService.STATUS_RUNNING)));

        assertEquals(0, service.cleanup(now));

        assertTrue(service.dynamicInfo.getHaltList().isEmpty());
        verify(service.taskAssignmentService, never()).closeAssignment(any(), any(), any(), any(Boolean.class));
        verify(service.urgentTaskService).cleanupBefore(LocalDate.of(2026, 7, 27));
    }

    private static FinalLoginSweepService service() {
        var service = new FinalLoginSweepService();
        service.accountMapper = mock(AccountMapper.class);
        service.logMapper = mock(LogMapper.class);
        service.dailyLoginService = mock(DailyLoginService.class);
        service.taskAssignmentService = mock(TaskAssignmentService.class);
        service.urgentTaskService = mock(UrgentTaskService.class);
        service.messageService = mock(MessageServiceImpl.class);
        service.logService = mock(LogServiceImpl.class);
        service.dynamicInfo = new DynamicInfo();
        when(service.taskAssignmentService.findAll()).thenReturn(List.of());
        return service;
    }

    private static AccountEntity account(Long id, LocalDateTime expireTime) {
        return new AccountEntity()
                .setId(id)
                .setName("账号" + id)
                .setAccount("account-" + id)
                .setTaskType("daily")
                .setFreeze(0)
                .setDelete(0)
                .setExpireTime(expireTime);
    }
}
