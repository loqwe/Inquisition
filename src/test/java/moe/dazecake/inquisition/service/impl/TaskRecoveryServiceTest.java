package moe.dazecake.inquisition.service.impl;

import moe.dazecake.inquisition.mapper.AccountMapper;
import moe.dazecake.inquisition.model.entity.AccountEntity;
import moe.dazecake.inquisition.model.entity.TaskAssignmentEntity;
import moe.dazecake.inquisition.model.local.DispatchIntent;
import moe.dazecake.inquisition.utils.DynamicInfo;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskRecoveryServiceTest {

    @Test
    void deviceOfflineBeforeGameStartRequeuesImmediately() {
        var service = new TaskRecoveryService();
        service.taskAssignmentService = mock(TaskAssignmentService.class);
        service.accountMapper = mock(AccountMapper.class);
        service.dynamicInfo = new DynamicInfo();
        service.messageService = mock(MessageServiceImpl.class);
        service.sklandCalibrationService = mock(SklandCalibrationService.class);
        service.dispatchQueueService = mock(DispatchQueueService.class);
        var assignment = assignment(0);
        var account = account();
        when(service.taskAssignmentService.findByDevice("device-1")).thenReturn(Optional.of(assignment));
        when(service.accountMapper.selectById(398L)).thenReturn(account);
        when(service.taskAssignmentService.closeAssignment(assignment, "REVOKED",
                "device offline before game start", true)).thenReturn(true);

        service.recoverDeviceOffline("device-1", LocalDateTime.of(2026, 7, 19, 12, 0));

        assertTrue(service.dynamicInfo.getHaltList().contains("device-1"));
        verify(service.taskAssignmentService).closeAssignment(assignment, "REVOKED",
                "device offline before game start", true);
        verify(service.messageService).push(account, "任务已回收", "设备离线，任务已重新排队");
    }

    @Test
    void startedTaskUsesSklandLastOnlineTimeBeforeRequeueing() {
        var service = new TaskRecoveryService();
        service.taskAssignmentService = mock(TaskAssignmentService.class);
        service.accountMapper = mock(AccountMapper.class);
        service.dynamicInfo = new DynamicInfo();
        service.messageService = mock(MessageServiceImpl.class);
        service.sklandCalibrationService = mock(SklandCalibrationService.class);
        service.dispatchQueueService = mock(DispatchQueueService.class);
        var assignment = assignment(1).setAssignedAt(LocalDateTime.of(2026, 7, 19, 11, 0));
        var account = account();
        var now = LocalDateTime.of(2026, 7, 19, 12, 0);
        when(service.taskAssignmentService.findByDevice("device-1")).thenReturn(Optional.of(assignment));
        when(service.accountMapper.selectById(398L)).thenReturn(account);
        when(service.sklandCalibrationService.calibrate(account, now)).thenReturn(Optional.of(
                new SklandCalibrationResult(20, 135, LocalDateTime.of(2026, 7, 19, 11, 30), now)));
        when(service.taskAssignmentService.closeAssignment(assignment, "REVOKED",
                "device offline after game activity was observed", false)).thenReturn(true);

        service.recoverDeviceOffline("device-1", now);

        assertEquals(now.plusMinutes(10), service.dynamicInfo.getFreezeUserInfoMap().get(398L));
        verify(service.dispatchQueueService).requeue(assignment);
        verify(service.messageService).push(account, "任务已回收", "设备离线，已校准游戏状态，10分钟后重新排队");
    }

    @Test
    void startedScheduledTaskRetriesTheSameRunBeforeRequeueing() {
        var service = new TaskRecoveryService();
        service.taskAssignmentService = mock(TaskAssignmentService.class);
        service.accountMapper = mock(AccountMapper.class);
        service.dynamicInfo = new DynamicInfo();
        service.messageService = mock(MessageServiceImpl.class);
        service.sklandCalibrationService = mock(SklandCalibrationService.class);
        service.dispatchQueueService = mock(DispatchQueueService.class);
        service.scheduledLifecycleService = mock(AccountScheduledRunLifecycleService.class);
        var assignment = assignment(1)
                .setDispatchSource(DispatchIntent.SOURCE_SCHEDULED)
                .setScheduledRunId(41L);
        var account = account();
        var now = LocalDateTime.of(2026, 7, 19, 12, 0);
        when(service.taskAssignmentService.findByDevice("device-1")).thenReturn(Optional.of(assignment));
        when(service.accountMapper.selectById(398L)).thenReturn(account);
        when(service.sklandCalibrationService.calibrate(account, now)).thenReturn(Optional.empty());
        when(service.taskAssignmentService.closeAssignment(assignment, "REVOKED",
                "device offline during task", false)).thenReturn(true);
        when(service.scheduledLifecycleService.retry(
                assignment, "device offline during task", now)).thenReturn(true);

        service.recoverDeviceOffline("device-1", now);

        verify(service.scheduledLifecycleService).retry(
                assignment, "device offline during task", now);
        verify(service.dispatchQueueService).requeue(assignment);
    }

    @Test
    void pendingModeChangeStopsStartedScheduledTaskFromReturningToItsOldQueue() {
        var service = new TaskRecoveryService();
        service.taskAssignmentService = mock(TaskAssignmentService.class);
        service.accountMapper = mock(AccountMapper.class);
        service.dynamicInfo = new DynamicInfo();
        service.messageService = mock(MessageServiceImpl.class);
        service.sklandCalibrationService = mock(SklandCalibrationService.class);
        service.dispatchQueueService = mock(DispatchQueueService.class);
        service.scheduledLifecycleService = mock(AccountScheduledRunLifecycleService.class);
        var assignment = assignment(1)
                .setDispatchSource(DispatchIntent.SOURCE_SCHEDULED)
                .setScheduledRunId(41L);
        var account = account();
        var now = LocalDateTime.of(2026, 7, 19, 12, 0);
        when(service.taskAssignmentService.findByDevice("device-1")).thenReturn(Optional.of(assignment));
        when(service.accountMapper.selectById(398L)).thenReturn(account);
        when(service.sklandCalibrationService.calibrate(account, now)).thenReturn(Optional.empty());
        when(service.taskAssignmentService.closeAssignment(assignment, "REVOKED",
                "device offline during task", false)).thenReturn(true);
        when(service.scheduledLifecycleService.retry(
                assignment, "device offline during task", now)).thenReturn(false);

        service.recoverDeviceOffline("device-1", now);

        verify(service.dispatchQueueService, never()).requeue(any());
        assertFalse(service.dynamicInfo.getFreezeUserInfoMap().containsKey(398L));
        verify(service.messageService).push(account, "任务已回收",
                "设备离线，调度配置已切换，旧任务不再排队");
    }

    @Test
    void delayedOfflineRecoveryDoesNotRevokeANewerAssignment() {
        var service = new TaskRecoveryService();
        service.taskAssignmentService = mock(TaskAssignmentService.class);
        service.accountMapper = mock(AccountMapper.class);
        service.dynamicInfo = new DynamicInfo();
        service.dispatchQueueService = mock(DispatchQueueService.class);
        var offlineAt = LocalDateTime.of(2026, 7, 19, 12, 0);
        var newerAssignment = assignment(0).setAssignedAt(offlineAt.plusSeconds(1));
        when(service.taskAssignmentService.findByDevice("device-1"))
                .thenReturn(Optional.of(newerAssignment));

        service.recoverDeviceOffline("device-1", offlineAt);

        assertFalse(service.dynamicInfo.getHaltList().contains("device-1"));
        verify(service.accountMapper, never()).selectById(any());
        verify(service.taskAssignmentService, never()).closeAssignment(
                any(), any(), any(), any(Boolean.class));
    }

    private TaskAssignmentEntity assignment(int started) {
        return new TaskAssignmentEntity()
                .setAssignmentId("assignment-1")
                .setAccountId(398L)
                .setDeviceToken("device-1")
                .setAssignedAt(LocalDateTime.of(2026, 7, 19, 11, 0))
                .setGameStarted(started);
    }

    private AccountEntity account() {
        return new AccountEntity().setId(398L).setName("账号774").setDelete(0);
    }
}
