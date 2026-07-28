package moe.dazecake.inquisition.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import moe.dazecake.inquisition.mapper.AccountMapper;
import moe.dazecake.inquisition.mapper.DeviceMapper;
import moe.dazecake.inquisition.model.entity.AccountEntity;
import moe.dazecake.inquisition.model.entity.DeviceEntity;
import moe.dazecake.inquisition.model.entity.TaskAssignmentEntity;
import moe.dazecake.inquisition.model.entity.UrgentTaskEntity;
import moe.dazecake.inquisition.model.entity.ConfigEntitySet.ConfigEntity;
import moe.dazecake.inquisition.model.entity.ConfigEntitySet.Fight;
import moe.dazecake.inquisition.model.local.WorkUser;
import moe.dazecake.inquisition.model.local.DispatchIntent;
import moe.dazecake.inquisition.model.vo.account.AccountCooldownVO;
import moe.dazecake.inquisition.utils.DynamicInfo;
import moe.dazecake.inquisition.utils.GameDayClock;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Optional;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
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
        service.dispatchQueueService = mock(DispatchQueueService.class);

        var account = new AccountEntity()
                .setId(423L)
                .setName("账号172B1")
                .setAccount("18307339567")
                .setServer(1L)
                .setTaskType("daily");

        service.errorHandle(account, "device-1", "lineBusy");

        assertTrue(service.dynamicInfo.getFreezeUserInfoMap().containsKey(423L));
        assertEquals("lineBusy", service.dynamicInfo.getCooldownReasonMap().get(423L));
        verify(service.dispatchQueueService).restoreBest(eq(423L), any(LocalDateTime.class));
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
    void normalTaskEndingWithoutALoginReturnsItsRunningUrgencyToTheFrontQueue() {
        var service = taskCompletionService();
        var assignment = assignment("assignment-normal").setTaskMode(TaskAssignmentService.MODE_NORMAL);
        var urgent = new UrgentTaskEntity().setId(12L).setAccountId(398L)
                .setStatus(UrgentTaskService.STATUS_RUNNING);
        when(service.taskAssignmentService.findByDevice("device-1")).thenReturn(Optional.of(assignment));
        when(service.taskAssignmentService.matchesSubmission(assignment, "device-1", "assignment-normal"))
                .thenReturn(true);
        when(service.taskAssignmentService.closeAssignment(
                assignment, "COMPLETED", "device reported completion", false)).thenReturn(true);
        when(service.urgentTaskService.findActiveByAccount(eq(398L), any())).thenReturn(Optional.of(urgent));

        var result = service.completeTask("device-1", "assignment-normal", null);

        assertEquals(200, result.getCode());
        verify(service.dispatchQueueService).restoreBest(eq(398L), any(LocalDateTime.class));
        verify(service.urgentTaskService).markWaiting(eq(12L), any(LocalDateTime.class));
    }

    @Test
    void staleLoginOnlyCompletionCannotFallThroughAsACompletedDailyTask() {
        var service = taskCompletionService();
        var assignment = assignment("assignment-stale-login")
                .setTaskMode(UrgentTaskService.MODE_LOGIN_ONLY)
                .setUrgentTaskId(12L);
        when(service.taskAssignmentService.findByDevice("device-1")).thenReturn(Optional.of(assignment));
        when(service.taskAssignmentService.matchesSubmission(
                assignment, "device-1", "assignment-stale-login")).thenReturn(true);
        when(service.urgentTaskService.findActiveByAccount(eq(398L), any())).thenReturn(Optional.empty());
        when(service.taskAssignmentService.closeAssignment(
                assignment, "STALE_LOGIN_ONLY", "urgent game day ended", true)).thenReturn(true);

        var result = service.completeTask("device-1", "assignment-stale-login", null);

        assertEquals(200, result.getCode());
        verify(service.taskAssignmentService).closeAssignment(
                assignment, "STALE_LOGIN_ONLY", "urgent game day ended", true);
        verify(service.messageService, never()).push(any(AccountEntity.class), contains("任务完成"), any());
        verify(service.sanityOcrService, never()).submit(any(), any(), any());
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
    void loginOnlyCompletionWithoutSuccessfulLoginLogEntersRetryInsteadOfCompleting() {
        var service = taskCompletionService();
        var assignment = assignment("assignment-login")
                .setTaskMode(UrgentTaskService.MODE_LOGIN_ONLY)
                .setUrgentTaskId(11L);
        var urgent = new UrgentTaskEntity().setId(11L).setAccountId(398L)
                .setGameDay(GameDayClock.gameDay(LocalDateTime.now()))
                .setStatus(UrgentTaskService.STATUS_RUNNING);
        var retryUntil = LocalDateTime.now().plusMinutes(10);
        when(service.taskAssignmentService.findByDevice("device-1")).thenReturn(Optional.of(assignment));
        when(service.taskAssignmentService.matchesSubmission(assignment, "device-1", "assignment-login"))
                .thenReturn(true);
        when(service.urgentTaskService.findActiveByAccount(eq(398L), any())).thenReturn(Optional.of(urgent));
        when(service.taskAssignmentService.closeAssignment(assignment, "FAILED",
                "login-only completed without successful login", false)).thenReturn(true);
        when(service.accountRuntimeService.recordFailure(any(AccountEntity.class), eq("device-1"),
                eq("LOGIN_NOT_CONFIRMED"), any(LocalDateTime.class))).thenReturn(retryUntil);

        var result = service.completeTask("device-1", "assignment-login", null);

        assertEquals(200, result.getCode());
        assertEquals(retryUntil, service.dynamicInfo.getFreezeUserInfoMap().get(398L));
        verify(service.dispatchQueueService).restoreBest(eq(398L), any(LocalDateTime.class));
        verify(service.urgentTaskService).markRetry(eq(urgent), eq("LOGIN_NOT_CONFIRMED"),
                eq(retryUntil), any(LocalDateTime.class));
        verify(service.sanityOcrService, never()).submit(any(), any(), any());
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
        var urgent = new UrgentTaskEntity().setId(11L).setAccountId(398L)
                .setStatus(UrgentTaskService.STATUS_RUNNING);
        when(service.urgentTaskService.findActiveByAccount(eq(398L), any())).thenReturn(Optional.of(urgent));

        var result = service.failTask("device-1", "assignment-current", "network", null);

        assertEquals(200, result.getCode());
        assertEquals(retryUntil, service.dynamicInfo.getFreezeUserInfoMap().get(398L));
        assertEquals("retryBackoff", service.dynamicInfo.getCooldownReasonMap().get(398L));
        verify(service.dispatchQueueService, org.mockito.Mockito.atLeastOnce())
                .restoreBest(eq(398L), any(LocalDateTime.class));
        verify(service.accountRuntimeService).recordFailure(any(AccountEntity.class), eq("device-1"),
                eq("network"), any(LocalDateTime.class));
        verify(service.urgentTaskService).markRetry(eq(urgent), eq("network"), eq(retryUntil),
                any(LocalDateTime.class));
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
        service.urgentTaskService = mock(UrgentTaskService.class);
        service.dispatchQueueService = mock(DispatchQueueService.class);
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
        service.urgentTaskService = mock(UrgentTaskService.class);
        service.dispatchQueueService = mock(DispatchQueueService.class);
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
    void twentySixUrgentTasksStayAheadOfAdministratorInsertedNormalTasks() {
        var service = new TaskServiceImpl();
        service.dynamicInfo = new DynamicInfo();
        service.urgentTaskService = mock(UrgentTaskService.class);
        service.dispatchQueueService = mock(DispatchQueueService.class);
        service.dynamicInfo.setWaitUserList(new ArrayList<>(List.of(99L, 7L, 8L)));
        var now = LocalDateTime.of(2026, 7, 28, 2, 10);
        when(service.urgentTaskService.findDispatchable(any(), eq(now))).thenReturn(List.of(
                new UrgentTaskEntity().setId(11L).setAccountId(7L)
                        .setStatus(UrgentTaskService.STATUS_WAITING)
                        .setTaskMode(UrgentTaskService.MODE_LOGIN_ONLY)
                        .setPriority(UrgentTaskService.PRIORITY_TWENTY_SIX)));

        var urgentByAccount = service.promoteReadyUrgentTasks(now);

        verify(service.dispatchQueueService).enqueueUrgent(7L, now);
        assertEquals(11L, urgentByAccount.get(7L).getId());
    }

    @Test
    void loginOnlyPayloadKeepsDailyProtocolAndDoesNotMutateStoredConfiguration() {
        var service = new TaskServiceImpl();
        var storedConfig = new ConfigEntity();
        storedConfig.getDaily().setFight(new ArrayList<>(List.of(new Fight("1-7", 3))));
        storedConfig.getDaily().setMail(true);
        storedConfig.getDaily().setFriend(true);
        storedConfig.getDaily().setCredit(true);
        storedConfig.getDaily().setTask(true);
        storedConfig.getDaily().setActivity(true);
        var account = new AccountEntity().setId(7L).setTaskType("daily").setConfig(storedConfig);
        var assignment = new TaskAssignmentEntity().setAssignmentId("assignment-login")
                .setTaskMode(UrgentTaskService.MODE_LOGIN_ONLY).setUrgentTaskId(11L);

        var payload = service.buildTaskAccountDTO(account, assignment);

        assertEquals("daily", payload.getTaskType());
        assertEquals("assignment-login", payload.getAssignmentId());
        assertTrue(payload.getConfig().getDaily().getFight().isEmpty());
        assertTrue(payload.getConfig().getDaily().getPlan().isEmpty());
        assertTrue(!payload.getConfig().getDaily().isMail());
        assertTrue(!payload.getConfig().getDaily().isFriend());
        assertTrue(!payload.getConfig().getDaily().isCredit());
        assertTrue(!payload.getConfig().getDaily().isTask());
        assertTrue(!payload.getConfig().getDaily().isActivity());
        assertEquals(Boolean.FALSE, payload.getConfig().getDaily().getShopping());
        assertEquals(false, new ObjectMapper().valueToTree(payload.getConfig().getDaily())
                .get("shopping").asBoolean());
        assertTrue(!payload.getConfig().getDaily().getInfrastructure().isHarvest());
        assertTrue(!payload.getConfig().getDaily().getOffer().isEnable());

        assertEquals(1, storedConfig.getDaily().getFight().size());
        assertTrue(storedConfig.getDaily().isMail());
        assertTrue(storedConfig.getDaily().isFriend());
    }

    @Test
    void normalPayloadDoesNotExposeTheLoginOnlyShoppingFlagToLegacyClients() {
        var service = new TaskServiceImpl();
        var account = new AccountEntity().setId(7L).setTaskType("daily").setConfig(new ConfigEntity());
        var assignment = new TaskAssignmentEntity().setAssignmentId("assignment-normal")
                .setTaskMode(TaskAssignmentService.MODE_NORMAL);

        var payload = service.buildTaskAccountDTO(account, assignment);
        var serializedDaily = new ObjectMapper().valueToTree(payload.getConfig().getDaily());

        assertTrue(!serializedDaily.has("shopping"));
    }

    @Test
    void forceHaltWorkListPathUsesHaltListLock() {
        var service = new TaskServiceImpl();
        service.dynamicInfo = new DynamicInfo();
        service.taskAssignmentService = mock(TaskAssignmentService.class);
        service.dispatchQueueService = mock(DispatchQueueService.class);
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
        verify(service.dispatchQueueService).remove(398L);
    }

    @Test
    void forceLoadRebuildsOnlyTheAutoLayer() {
        var service = new TaskServiceImpl();
        service.dynamicInfo = new DynamicInfo();
        service.accountMapper = mock(AccountMapper.class);
        service.dispatchQueueService = mock(DispatchQueueService.class);
        service.logService = mock(LogServiceImpl.class);
        when(service.accountMapper.selectList(any())).thenReturn(List.of(
                new AccountEntity().setId(1L), new AccountEntity().setId(2L)));

        assertEquals(200, service.forceLoadAllTask().getCode());

        verify(service.dispatchQueueService).replaceAutos(eq(List.of(1L, 2L)),
                any(LocalDateTime.class));
    }

    @Test
    void generatedScheduledRunDoesNotRecheckTheCurrentActivationWeekday() {
        var service = spy(new TaskServiceImpl());
        service.dynamicInfo = new DynamicInfo();
        service.deviceMapper = mock(DeviceMapper.class);
        service.accountMapper = mock(AccountMapper.class);
        service.deviceRuntimeService = mock(DeviceRuntimeService.class);
        service.taskAssignmentService = mock(TaskAssignmentService.class);
        service.urgentTaskService = mock(UrgentTaskService.class);
        service.dispatchQueueService = mock(DispatchQueueService.class);
        service.messageService = mock(MessageServiceImpl.class);
        var account = new AccountEntity().setId(7L).setDelete(0).setFreeze(0)
                .setServer(0L).setTaskType("daily")
                .setExpireTime(LocalDateTime.of(2099, 1, 1, 0, 0));
        var intent = DispatchIntent.scheduled(7L, 41L,
                LocalDateTime.of(2026, 7, 28, 19, 30));
        var assignment = new TaskAssignmentEntity().setAssignmentId("scheduled-assignment")
                .setAccountId(7L).setTaskMode(TaskAssignmentService.MODE_NORMAL);
        when(service.deviceMapper.selectOne(any())).thenReturn(new DeviceEntity()
                .setDeviceToken("device-1").setDelete(0));
        when(service.deviceRuntimeService.hasFreshHeartbeat(eq("device-1"), any())).thenReturn(true);
        when(service.taskAssignmentService.findByDevice("device-1")).thenReturn(Optional.empty());
        when(service.dispatchQueueService.snapshot()).thenReturn(List.of(7L));
        when(service.dispatchQueueService.resolve(eq(7L), any())).thenReturn(intent);
        when(service.accountMapper.selectById(7L)).thenReturn(account);
        when(service.taskAssignmentService.createAssignment(eq(account), eq("device-1"), any(),
                eq(TaskAssignmentService.MODE_NORMAL), eq(null), eq(intent)))
                .thenReturn(assignment);
        doReturn(false).when(service).checkActivationTime(account);

        var result = service.getTask("device-1");

        assertEquals(200, result.getCode());
        assertEquals("scheduled-assignment", result.getData().getAssignmentId());
        verify(service.dispatchQueueService).dequeue(intent);
    }

    @Test
    void scheduledFailureKeepsTheSameRunAtTheFinalRetryTime() {
        var service = taskCompletionService();
        var assignment = assignment("scheduled-assignment")
                .setDispatchSource(DispatchIntent.SOURCE_SCHEDULED)
                .setScheduledRunId(41L);
        var retryUntil = LocalDateTime.now().plusMinutes(30);
        when(service.taskAssignmentService.findByDevice("device-1")).thenReturn(Optional.of(assignment));
        when(service.taskAssignmentService.matchesSubmission(
                assignment, "device-1", "scheduled-assignment")).thenReturn(true);
        when(service.taskAssignmentService.closeAssignment(
                assignment, "FAILED", "network", false)).thenReturn(true);
        when(service.accountRuntimeService.recordFailure(any(AccountEntity.class), eq("device-1"),
                eq("network"), any(LocalDateTime.class))).thenReturn(retryUntil);
        when(service.scheduledLifecycleService.retry(assignment, "network", retryUntil)).thenReturn(true);

        assertEquals(200, service.failTask(
                "device-1", "scheduled-assignment", "network", null).getCode());

        verify(service.scheduledLifecycleService).retry(assignment, "network", retryUntil);
        verify(service.dispatchQueueService).restoreBest(eq(398L), any(LocalDateTime.class));
    }

    @Test
    void pendingModeChangePreventsFailedScheduledAssignmentFromRequeueing() {
        var service = taskCompletionService();
        var assignment = assignment("scheduled-assignment")
                .setDispatchSource(DispatchIntent.SOURCE_SCHEDULED)
                .setScheduledRunId(41L);
        var retryUntil = LocalDateTime.now().plusMinutes(30);
        when(service.taskAssignmentService.findByDevice("device-1")).thenReturn(Optional.of(assignment));
        when(service.taskAssignmentService.matchesSubmission(
                assignment, "device-1", "scheduled-assignment")).thenReturn(true);
        when(service.taskAssignmentService.closeAssignment(
                assignment, "FAILED", "network", false)).thenReturn(true);
        when(service.accountRuntimeService.recordFailure(any(AccountEntity.class), eq("device-1"),
                eq("network"), any(LocalDateTime.class))).thenReturn(retryUntil);
        when(service.scheduledLifecycleService.retry(assignment, "network", retryUntil)).thenReturn(false);

        assertEquals(200, service.failTask(
                "device-1", "scheduled-assignment", "network", null).getCode());

        verify(service.dispatchQueueService, never()).restoreBest(any(), any());
    }

    @Test
    void sanityThresholdDoesNotResetWhenScheduledModeRejectsAutoAdmission() {
        var service = new TaskServiceImpl();
        service.dynamicInfo = new DynamicInfo();
        service.accountMapper = mock(AccountMapper.class);
        service.dispatchQueueService = mock(DispatchQueueService.class);
        service.scheduledLifecycleService = mock(AccountScheduledRunLifecycleService.class);
        service.messageService = mock(MessageServiceImpl.class);
        var account = new AccountEntity().setId(7L).setDelete(0).setFreeze(0)
                .setExpireTime(LocalDateTime.of(2099, 1, 1, 0, 0));
        service.dynamicInfo.setUserSan(7L, 94, 135);
        when(service.accountMapper.selectById(7L)).thenReturn(account);
        when(service.dispatchQueueService.enqueueAuto(7L)).thenReturn(false);

        service.calculatingSan();

        assertEquals(95, service.dynamicInfo.getUserSanInfoMap().get(7L).getSan());
        verify(service.messageService, never()).push(eq(account), eq("等待分配作战服务器"), any());
    }

    private TaskServiceImpl taskCompletionService() {
        var service = new TaskServiceImpl();
        service.dynamicInfo = new DynamicInfo();
        service.accountMapper = mock(AccountMapper.class);
        service.messageService = mock(MessageServiceImpl.class);
        service.logService = mock(LogServiceImpl.class);
        service.taskAssignmentService = mock(TaskAssignmentService.class);
        service.deviceRuntimeService = mock(DeviceRuntimeService.class);
        service.accountRuntimeService = mock(AccountRuntimeService.class);
        service.sanityOcrService = mock(SanityOcrService.class);
        service.urgentTaskService = mock(UrgentTaskService.class);
        service.dispatchQueueService = mock(DispatchQueueService.class);
        service.scheduledLifecycleService = mock(AccountScheduledRunLifecycleService.class);
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
