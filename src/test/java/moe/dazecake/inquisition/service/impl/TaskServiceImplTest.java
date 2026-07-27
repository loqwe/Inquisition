package moe.dazecake.inquisition.service.impl;

import moe.dazecake.inquisition.mapper.AccountMapper;
import moe.dazecake.inquisition.mapper.DeviceMapper;
import moe.dazecake.inquisition.model.entity.AccountEntity;
import moe.dazecake.inquisition.model.entity.DeviceEntity;
import moe.dazecake.inquisition.model.entity.TaskAssignmentEntity;
import moe.dazecake.inquisition.model.local.WorkUser;
import moe.dazecake.inquisition.model.vo.account.AccountCooldownVO;
import moe.dazecake.inquisition.utils.DynamicInfo;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskServiceImplTest {

    @Test
    void lineBusyCooldownWritesReasonLogAndAdminNotice() {
        var service = new TaskServiceImpl();
        service.dynamicInfo = new DynamicInfo();
        service.logService = mock(LogServiceImpl.class);
        service.messageService = mock(MessageServiceImpl.class);
        service.accountMapper = mock(AccountMapper.class);

        var account = new AccountEntity()
                .setId(423L)
                .setName("账号172B1")
                .setAccount("18307339567")
                .setServer(1L)
                .setTaskType("daily");

        service.errorHandle(account, "device-1", "lineBusy");

        assertTrue(service.dynamicInfo.getFreezeUserInfoMap().containsKey(423L));
        assertEquals("lineBusy", service.dynamicInfo.getCooldownReasonMap().get(423L));
        assertTrue(service.dynamicInfo.getWaitUserList().contains(423L));
        verify(service.logService).logWarn(contains("账号临时冷却"), contains("账号172B1"));
        verify(service.messageService, never()).pushAdmin(contains("账号临时冷却"), contains("lineBusy"));
    }

    @Test
    void activeCooldownTaskMapContainsAccountAndReason() {
        var service = new TaskServiceImpl();
        service.dynamicInfo = new DynamicInfo();
        service.accountMapper = mock(AccountMapper.class);

        var until = LocalDateTime.now().plusMinutes(30);
        service.dynamicInfo.getFreezeUserInfoMap().put(423L, until);
        service.dynamicInfo.getCooldownReasonMap().put(423L, "lineBusy");

        when(service.accountMapper.selectById(423L)).thenReturn(new AccountEntity()
                .setId(423L)
                .setName("账号172B1")
                .setAccount("18307339567")
                .setFreeze(0)
                .setDelete(0)
                .setExpireTime(LocalDateTime.now().plusDays(1)));

        AccountCooldownVO vo = service.getActiveCooldownTaskInfoMap().get(423L);

        assertNotNull(vo);
        assertEquals("账号172B1", vo.getName());
        assertEquals("18307339567", vo.getAccount());
        assertEquals(until, vo.getUntil());
        assertEquals("lineBusy", vo.getReason());
    }

    @Test
    void completeTaskRejectsAStaleAssignmentId() {
        var service = taskCompletionService();
        var assignment = assignment("assignment-current");
        when(service.taskAssignmentService.findByDevice("device-1")).thenReturn(Optional.of(assignment));

        var result = service.completeTask("device-1", "assignment-stale", null);

        assertEquals(409, result.getCode());
        verify(service.taskAssignmentService, never()).closeAssignment(
                eq(assignment), eq("COMPLETED"), eq("device reported completion"), eq(false));
    }

    @Test
    void completeTaskArchivesTheCurrentAssignment() {
        var service = taskCompletionService();
        var assignment = assignment("assignment-current");
        when(service.taskAssignmentService.findByDevice("device-1")).thenReturn(Optional.of(assignment));
        when(service.taskAssignmentService.matchesSubmission(assignment, "device-1", "assignment-current"))
                .thenReturn(true);
        when(service.taskAssignmentService.closeAssignment(
                assignment, "COMPLETED", "device reported completion", false)).thenReturn(true);

        var result = service.completeTask("device-1", "assignment-current", null);

        assertEquals(200, result.getCode());
        verify(service.taskAssignmentService).closeAssignment(
                assignment, "COMPLETED", "device reported completion", false);
    }

    @Test
    void completedTaskSubmitsItsScreenshotForAsynchronousSanityOcr() {
        var service = taskCompletionService();
        var assignment = assignment("assignment-current");
        when(service.taskAssignmentService.findByDevice("device-1")).thenReturn(Optional.of(assignment));
        when(service.taskAssignmentService.matchesSubmission(assignment, "device-1", "assignment-current"))
                .thenReturn(true);
        when(service.taskAssignmentService.closeAssignment(
                assignment, "COMPLETED", "device reported completion", false)).thenReturn(true);

        var result = service.completeTask("device-1", "assignment-current",
                "https://inquisition-img.example/one.png");

        assertEquals(200, result.getCode());
        verify(service.sanityOcrService).submit(eq(398L),
                eq("https://inquisition-img.example/one.png"), any(LocalDateTime.class));
    }

    @Test
    void legacyCompletionWithoutAssignmentIdClosesCurrentDeviceAssignment() {
        var service = taskCompletionService();
        var assignment = assignment("assignment-current");
        when(service.taskAssignmentService.findByDevice("device-1")).thenReturn(Optional.of(assignment));
        when(service.taskAssignmentService.matchesSubmission(assignment, "device-1", null)).thenReturn(true);
        when(service.taskAssignmentService.closeAssignment(
                assignment, "COMPLETED", "device reported completion", false)).thenReturn(true);

        var result = service.completeTask("device-1", null, null);

        assertEquals(200, result.getCode());
        verify(service.taskAssignmentService).closeAssignment(
                assignment, "COMPLETED", "device reported completion", false);
    }

    @Test
    void failedTaskPersistsRetryBackoffAndRequeuesTheAccount() {
        var service = taskCompletionService();
        service.logService = mock(LogServiceImpl.class);
        var assignment = assignment("assignment-current");
        var retryUntil = LocalDateTime.now().plusMinutes(30);
        when(service.taskAssignmentService.findByDevice("device-1")).thenReturn(Optional.of(assignment));
        when(service.taskAssignmentService.matchesSubmission(assignment, "device-1", "assignment-current"))
                .thenReturn(true);
        when(service.taskAssignmentService.closeAssignment(
                assignment, "FAILED", "network", false)).thenReturn(true);
        when(service.accountRuntimeService.recordFailure(any(AccountEntity.class), eq("device-1"),
                eq("network"), any(LocalDateTime.class))).thenReturn(retryUntil);
        when(service.deviceRuntimeService.recordTaskFailure(eq("device-1"), any(LocalDateTime.class)))
                .thenReturn(false);

        var result = service.failTask("device-1", "assignment-current", "network", null);

        assertEquals(200, result.getCode());
        assertEquals(retryUntil, service.dynamicInfo.getFreezeUserInfoMap().get(398L));
        assertEquals("retryBackoff", service.dynamicInfo.getCooldownReasonMap().get(398L));
        assertTrue(service.dynamicInfo.getWaitUserList().contains(398L));
        verify(service.accountRuntimeService).recordFailure(any(AccountEntity.class), eq("device-1"),
                eq("network"), any(LocalDateTime.class));
    }

    @Test
    void failedTaskUsesOneHourDeviceSuspensionAfterRepeatedDeviceFailure() {
        var service = taskCompletionService();
        service.logService = mock(LogServiceImpl.class);
        var assignment = assignment("assignment-current");
        var retryUntil = LocalDateTime.of(2026, 7, 19, 13, 2);
        when(service.taskAssignmentService.findByDevice("device-1")).thenReturn(Optional.of(assignment));
        when(service.taskAssignmentService.matchesSubmission(assignment, "device-1", "assignment-current"))
                .thenReturn(true);
        when(service.taskAssignmentService.closeAssignment(
                assignment, "FAILED", "network", false)).thenReturn(true);
        when(service.accountRuntimeService.recordFailure(any(AccountEntity.class), eq("device-1"),
                eq("network"), any(LocalDateTime.class))).thenReturn(retryUntil);
        when(service.deviceRuntimeService.recordTaskFailure(eq("device-1"), any(LocalDateTime.class)))
                .thenReturn(true);

        var before = LocalDateTime.now();
        var result = service.failTask("device-1", "assignment-current", "network", null);

        assertEquals(200, result.getCode());
        var until = service.dynamicInfo.getFreezeUserInfoMap().get(398L);
        assertTrue(until != null && !until.isBefore(before.plusMinutes(59)));
        assertEquals("deviceRepeatedFailure", service.dynamicInfo.getCooldownReasonMap().get(398L));
        verify(service.messageService).push(any(AccountEntity.class), eq("设备异常"), contains("暂停1小时"));
    }

    @Test
    void haltedDeviceCannotReceiveAnotherTaskBeforeAcknowledgement() {
        var service = new TaskServiceImpl();
        service.dynamicInfo = new DynamicInfo();
        service.dynamicInfo.getHaltList().add("device-1");

        var result = service.getTask("device-1");

        assertEquals(500, result.getCode());
        assertEquals("设备等待停机确认，暂不分配新任务", result.getMsg());
    }

    @Test
    void deviceWithoutFreshHeartbeatCannotReceiveTask() {
        var service = new TaskServiceImpl();
        service.dynamicInfo = new DynamicInfo();
        service.deviceMapper = mock(DeviceMapper.class);
        service.accountMapper = mock(AccountMapper.class);
        service.deviceRuntimeService = mock(DeviceRuntimeService.class);
        service.taskAssignmentService = mock(TaskAssignmentService.class);
        when(service.deviceMapper.selectOne(any())).thenReturn(new DeviceEntity()
                .setDeviceToken("device-1").setDelete(0));
        when(service.deviceRuntimeService.hasFreshHeartbeat(eq("device-1"), any(LocalDateTime.class)))
                .thenReturn(false);

        var result = service.getTask("device-1");

        assertEquals(429, result.getCode());
        assertEquals("设备心跳已过期，暂不分配任务", result.getMsg());
        verify(service.taskAssignmentService, never()).findByDevice("device-1");
    }

    @Test
    void deviceWithFreshHeartbeatCanReachTaskLookup() {
        var service = new TaskServiceImpl();
        service.dynamicInfo = new DynamicInfo();
        service.deviceMapper = mock(DeviceMapper.class);
        service.accountMapper = mock(AccountMapper.class);
        service.deviceRuntimeService = mock(DeviceRuntimeService.class);
        service.taskAssignmentService = mock(TaskAssignmentService.class);
        when(service.deviceMapper.selectOne(any())).thenReturn(new DeviceEntity()
                .setDeviceToken("device-1").setDelete(0));
        when(service.deviceRuntimeService.hasFreshHeartbeat(eq("device-1"), any(LocalDateTime.class)))
                .thenReturn(true);
        when(service.taskAssignmentService.findByDevice("device-1")).thenReturn(Optional.empty());

        var result = service.getTask("device-1");

        assertEquals(200, result.getCode());
        assertEquals("待分配队列为空", result.getMsg());
        verify(service.taskAssignmentService).findByDevice("device-1");
    }

    @Test
    void forceHaltWorkListPathUsesHaltListLock() {
        var service = new TaskServiceImpl();
        service.dynamicInfo = new DynamicInfo();
        service.taskAssignmentService = mock(TaskAssignmentService.class);
        when(service.taskAssignmentService.findByAccount(398L)).thenReturn(Optional.empty());
        service.dynamicInfo.getWorkUserList().add(398L);
        var workUser = new WorkUser();
        workUser.setDeviceToken("device-1");
        service.dynamicInfo.getWorkUserInfoMap().put(398L, workUser);
        var haltList = new LockCheckingHaltList();
        service.dynamicInfo.setHaltList(haltList);

        service.forceHaltTask(398L);

        assertEquals(1, haltList.size());
        assertEquals("device-1", haltList.get(0));
    }

    private TaskServiceImpl taskCompletionService() {
        var service = new TaskServiceImpl();
        service.dynamicInfo = new DynamicInfo();
        service.accountMapper = mock(AccountMapper.class);
        service.messageService = mock(MessageServiceImpl.class);
        service.taskAssignmentService = mock(TaskAssignmentService.class);
        service.deviceRuntimeService = mock(DeviceRuntimeService.class);
        service.accountRuntimeService = mock(AccountRuntimeService.class);
        service.sanityOcrService = mock(SanityOcrService.class);
        when(service.accountMapper.selectById(398L)).thenReturn(new AccountEntity()
                .setId(398L)
                .setName("账号774")
                .setAccount("test-account")
                .setServer(0L)
                .setTaskType("daily"));
        return service;
    }

    private TaskAssignmentEntity assignment(String assignmentId) {
        return new TaskAssignmentEntity()
                .setAssignmentId(assignmentId)
                .setAccountId(398L)
                .setDeviceToken("device-1")
                .setTaskType("daily")
                .setAssignedAt(LocalDateTime.now().minusMinutes(10))
                .setLeaseExpiresAt(LocalDateTime.now().plusHours(1));
    }

    private static final class LockCheckingHaltList extends ArrayList<String> {
        @Override
        public boolean add(String value) {
            assertTrue(Thread.holdsLock(this), "haltList add must hold the list monitor");
            return super.add(value);
        }
    }
}
