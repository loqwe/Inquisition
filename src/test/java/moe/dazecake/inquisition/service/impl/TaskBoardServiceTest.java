package moe.dazecake.inquisition.service.impl;

import moe.dazecake.inquisition.mapper.AccountMapper;
import moe.dazecake.inquisition.mapper.DeviceMapper;
import moe.dazecake.inquisition.model.entity.AccountEntity;
import moe.dazecake.inquisition.model.entity.DeviceEntity;
import moe.dazecake.inquisition.model.entity.TaskAssignmentEntity;
import moe.dazecake.inquisition.model.entity.UrgentTaskEntity;
import moe.dazecake.inquisition.model.vo.account.AccountCooldownVO;
import moe.dazecake.inquisition.utils.DynamicInfo;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskBoardServiceTest {

    @Test
    void snapshotSeparatesTwentySixUrgencyFromNormalQueueAndSortsUrgentRunningFirst() {
        var service = service();
        var now = LocalDateTime.of(2026, 7, 28, 2, 20);
        var gameDay = LocalDate.of(2026, 7, 27);
        service.dynamicInfo.setWaitUserList(new java.util.ArrayList<>(List.of(99L, 7L, 8L)));
        var urgentWaiting = urgent(11L, 7L, gameDay, UrgentTaskService.STATUS_WAITING, now.minusMinutes(20));
        var urgentRunning = urgent(12L, 10L, gameDay, UrgentTaskService.STATUS_RUNNING, now.minusMinutes(15));
        var completed = urgent(13L, 8L, gameDay, UrgentTaskService.STATUS_SUCCEEDED, now.minusHours(1));
        when(service.urgentTaskService.findActiveForGameDay(gameDay))
                .thenReturn(List.of(urgentWaiting, urgentRunning));
        when(service.urgentTaskService.findAllForGameDay(gameDay))
                .thenReturn(List.of(urgentWaiting, urgentRunning, completed));
        var normalAssignment = assignment("normal", 9L, "device-b", TaskAssignmentService.MODE_NORMAL,
                now.minusMinutes(30));
        var urgentAssignment = assignment("urgent", 10L, "device-a", UrgentTaskService.MODE_LOGIN_ONLY,
                now.minusMinutes(10)).setUrgentTaskId(12L).setLastProgressTitle("开始登录");
        when(service.taskAssignmentService.findAll()).thenReturn(List.of(normalAssignment, urgentAssignment));
        when(service.deviceMapper.selectList(any())).thenReturn(List.of(
                new DeviceEntity().setDeviceToken("device-a").setDeviceName("A"),
                new DeviceEntity().setDeviceToken("device-b").setDeviceName("2")));
        when(service.accountMapper.selectBatchIds(any())).thenReturn(List.of(
                account(7L, "账号7"), account(8L, "账号8"), account(9L, "账号9"),
                account(10L, "账号10"), account(99L, "账号99")));
        when(service.accountMapper.selectList(any())).thenReturn(List.of(
                account(20L, "冻结账号").setFreeze(1)));
        var cooldowns = new HashMap<Long, AccountCooldownVO>();
        cooldowns.put(7L, new AccountCooldownVO(7L, "账号7", "account-7",
                now.plusMinutes(10), "retryBackoff", "递增重试"));
        when(service.taskService.getActiveCooldownTaskInfoMap()).thenReturn(cooldowns);

        var board = service.getBoard(now);

        assertEquals(now, board.getGeneratedAt());
        assertEquals(2, board.getUrgentTasks().size());
        assertEquals(List.of(99L, 8L), board.getPendingTasks().stream()
                .map(task -> task.getId()).collect(java.util.stream.Collectors.toList()));
        assertTrue(board.getPendingTasks().get(1).getReturnedFromUrgent());
        assertEquals(10L, board.getRunningTasks().get(0).getAccountId());
        assertTrue(board.getRunningTasks().get(0).getUrgent());
        assertEquals("A", board.getRunningTasks().get(0).getDeviceName());
        assertEquals(9L, board.getRunningTasks().get(1).getAccountId());
        assertEquals("2", board.getRunningTasks().get(1).getDeviceName());
        assertEquals(2, board.getSummary().getUrgent());
        assertEquals(2, board.getSummary().getPending());
        assertEquals(2, board.getSummary().getInProgress());
        assertEquals(1, board.getSummary().getCoolingDown());
        assertEquals(1, board.getSummary().getFrozen());
    }

    @Test
    void immediateRetryClearsCooldownAndMovesTheTwentySixTaskToTheFront() {
        var service = service();
        var now = LocalDateTime.of(2026, 7, 28, 2, 30);
        var urgent = urgent(11L, 7L, LocalDate.of(2026, 7, 27),
                UrgentTaskService.STATUS_RETRY_WAIT, now.minusMinutes(20));
        when(service.urgentTaskService.findActiveById(11L)).thenReturn(Optional.of(urgent));
        when(service.urgentTaskService.retryNow(11L, now)).thenReturn(true);
        service.dynamicInfo.setWaitUserList(new java.util.ArrayList<>(List.of(99L, 7L)));
        service.dynamicInfo.getFreezeUserInfoMap().put(7L, now.plusHours(1));
        service.dynamicInfo.getCooldownReasonMap().put(7L, "retryBackoff");

        assertTrue(service.retryUrgentTask(11L, now));

        verify(service.dispatchQueueService).enqueueUrgent(7L, now);
        assertTrue(!service.dynamicInfo.getFreezeUserInfoMap().containsKey(7L));
        verify(service.urgentTaskService).retryNow(11L, now);
    }

    @Test
    void cancellingUrgencyLeavesTheAccountInTheNormalQueue() {
        var service = service();
        var now = LocalDateTime.of(2026, 7, 28, 2, 30);
        var urgent = urgent(11L, 7L, LocalDate.of(2026, 7, 27),
                UrgentTaskService.STATUS_WAITING, now.minusMinutes(20));
        when(service.urgentTaskService.findActiveById(11L)).thenReturn(Optional.of(urgent));
        when(service.urgentTaskService.cancel(11L, now)).thenReturn(true);

        assertTrue(service.cancelUrgentTask(11L, now));

        verify(service.dispatchQueueService).restoreBest(7L, now);
    }

    private static TaskBoardService service() {
        var service = new TaskBoardService();
        service.accountMapper = mock(AccountMapper.class);
        service.deviceMapper = mock(DeviceMapper.class);
        service.taskAssignmentService = mock(TaskAssignmentService.class);
        service.urgentTaskService = mock(UrgentTaskService.class);
        service.taskService = mock(TaskServiceImpl.class);
        service.dynamicInfo = new DynamicInfo();
        service.dispatchQueueService = mock(DispatchQueueService.class);
        return service;
    }

    private static UrgentTaskEntity urgent(Long id, Long accountId, LocalDate gameDay,
                                            String status, LocalDateTime createdAt) {
        return new UrgentTaskEntity().setId(id).setAccountId(accountId).setGameDay(gameDay)
                .setTriggerType(UrgentTaskService.TRIGGER_TWENTY_SIX)
                .setTaskMode(UrgentTaskService.MODE_LOGIN_ONLY)
                .setPriority(UrgentTaskService.PRIORITY_TWENTY_SIX)
                .setStatus(status).setAttemptCount(1)
                .setCreatedAt(createdAt).setUpdatedAt(createdAt);
    }

    private static TaskAssignmentEntity assignment(String id, Long accountId, String device,
                                                     String mode, LocalDateTime assignedAt) {
        return new TaskAssignmentEntity().setAssignmentId(id).setAccountId(accountId)
                .setDeviceToken(device).setTaskType("daily").setTaskMode(mode)
                .setAssignedAt(assignedAt).setLastProgressAt(assignedAt.plusMinutes(1))
                .setLeaseExpiresAt(assignedAt.plusHours(2));
    }

    private static AccountEntity account(Long id, String name) {
        return new AccountEntity().setId(id).setName(name).setAccount("account-" + id)
                .setTaskType("daily").setAgent(0L).setFreeze(0).setDelete(0)
                .setExpireTime(LocalDateTime.of(2026, 8, 1, 0, 0));
    }
}
