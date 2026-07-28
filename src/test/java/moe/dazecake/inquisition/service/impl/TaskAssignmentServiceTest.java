package moe.dazecake.inquisition.service.impl;

import moe.dazecake.inquisition.mapper.TaskAssignmentHistoryMapper;
import moe.dazecake.inquisition.mapper.TaskAssignmentMapper;
import moe.dazecake.inquisition.model.entity.AccountEntity;
import moe.dazecake.inquisition.model.entity.TaskAssignmentEntity;
import moe.dazecake.inquisition.model.entity.TaskAssignmentHistoryEntity;
import moe.dazecake.inquisition.model.entity.UrgentTaskEntity;
import moe.dazecake.inquisition.model.local.DispatchIntent;
import moe.dazecake.inquisition.utils.DynamicInfo;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskAssignmentServiceTest {

    @Test
    void completedScheduledAssignmentCompletesTheSameRunWithoutRequeueing() {
        var service = assignmentService();
        var assignment = scheduledAssignment();
        when(service.assignmentMapper.deleteById("scheduled-assignment")).thenReturn(1);
        when(service.historyMapper.insert(any(TaskAssignmentHistoryEntity.class))).thenReturn(1);

        assertTrue(service.closeAssignment(
                assignment, "COMPLETED", "device reported completion", false));

        verify(service.scheduledLifecycleService).complete(eq(assignment), any(LocalDateTime.class));
        verify(service.scheduledLifecycleService, never()).retry(any(), any(), any());
        verify(service.dispatchQueueService, never()).requeue(any());
    }

    @Test
    void administratorTerminationCancelsTheScheduledRunWithoutRequeueing() {
        var service = assignmentService();
        var assignment = scheduledAssignment();
        when(service.assignmentMapper.deleteById("scheduled-assignment")).thenReturn(1);
        when(service.historyMapper.insert(any(TaskAssignmentHistoryEntity.class))).thenReturn(1);

        assertTrue(service.closeAssignment(
                assignment, "REVOKED", "administrator halted task", false));

        verify(service.scheduledLifecycleService).cancel(eq(assignment), any(LocalDateTime.class));
        verify(service.dispatchQueueService, never()).requeue(any());
    }

    @Test
    void scheduledAssignmentPersistsItsServerOnlySourceAndStartsTheSameRun() {
        var service = new TaskAssignmentService();
        service.assignmentMapper = mock(TaskAssignmentMapper.class);
        service.historyMapper = mock(TaskAssignmentHistoryMapper.class);
        service.urgentTaskService = mock(UrgentTaskService.class);
        service.scheduledLifecycleService = mock(AccountScheduledRunLifecycleService.class);
        service.dispatchQueueService = mock(DispatchQueueService.class);
        service.dynamicInfo = new DynamicInfo();
        when(service.assignmentMapper.insert(any(TaskAssignmentEntity.class))).thenReturn(1);
        var now = LocalDateTime.of(2026, 7, 28, 19, 30);
        var account = new AccountEntity().setId(7L).setTaskType("daily");
        var intent = DispatchIntent.scheduled(7L, 41L, now);

        var assignment = service.createAssignment(account, "device-1", now,
                TaskAssignmentService.MODE_NORMAL, null, intent);

        assertEquals(DispatchIntent.SOURCE_SCHEDULED, assignment.getDispatchSource());
        assertEquals(41L, assignment.getScheduledRunId());
        assertEquals(TaskAssignmentService.MODE_NORMAL, assignment.getTaskMode());
        verify(service.scheduledLifecycleService).start(intent);
        verify(service.assignmentMapper).insert(assignment);
    }

    @Test
    void closingScheduledAssignmentArchivesSourceAndSuppressesPendingModeRequeue() {
        var service = new TaskAssignmentService();
        service.assignmentMapper = mock(TaskAssignmentMapper.class);
        service.historyMapper = mock(TaskAssignmentHistoryMapper.class);
        service.urgentTaskService = mock(UrgentTaskService.class);
        service.scheduledLifecycleService = mock(AccountScheduledRunLifecycleService.class);
        service.dispatchQueueService = mock(DispatchQueueService.class);
        service.dynamicInfo = new DynamicInfo();
        when(service.assignmentMapper.deleteById("scheduled-assignment")).thenReturn(1);
        when(service.historyMapper.insert(any(TaskAssignmentHistoryEntity.class))).thenReturn(1);
        when(service.scheduledLifecycleService.retry(any(), any(), any())).thenReturn(false);
        var assignment = new TaskAssignmentEntity().setAssignmentId("scheduled-assignment")
                .setAccountId(7L).setDeviceToken("device-1").setTaskType("daily")
                .setTaskMode(TaskAssignmentService.MODE_NORMAL)
                .setDispatchSource(DispatchIntent.SOURCE_SCHEDULED)
                .setScheduledRunId(41L)
                .setAssignedAt(LocalDateTime.of(2026, 7, 28, 19, 30));

        assertTrue(service.closeAssignment(assignment, "TIMED_OUT", "two hour limit", true));

        var history = ArgumentCaptor.forClass(TaskAssignmentHistoryEntity.class);
        verify(service.historyMapper).insert(history.capture());
        assertEquals(DispatchIntent.SOURCE_SCHEDULED, history.getValue().getDispatchSource());
        assertEquals(41L, history.getValue().getScheduledRunId());
        verify(service.scheduledLifecycleService).retry(eq(assignment), eq("two hour limit"),
                any(LocalDateTime.class));
        verify(service.dispatchQueueService, never()).requeue(assignment);
    }

    @Test
    void createsATwoHourHardLeaseAndMirrorsItInMemory() {
        var service = new TaskAssignmentService();
        service.assignmentMapper = mock(TaskAssignmentMapper.class);
        service.historyMapper = mock(TaskAssignmentHistoryMapper.class);
        service.urgentTaskService = mock(UrgentTaskService.class);
        service.dynamicInfo = new DynamicInfo();
        service.dispatchQueueService = mock(DispatchQueueService.class);
        when(service.assignmentMapper.insert(any(TaskAssignmentEntity.class))).thenReturn(1);

        var now = LocalDateTime.of(2026, 7, 18, 13, 0);
        var account = new AccountEntity().setId(398L).setTaskType("daily");

        var assignment = service.createAssignment(account, "device-1", now);

        assertNotNull(assignment.getAssignmentId());
        assertEquals(now.plusHours(2), assignment.getLeaseExpiresAt());
        assertEquals(398L, service.dynamicInfo.getUserIdByDeviceToken("device-1"));
        assertEquals(assignment.getAssignmentId(),
                service.dynamicInfo.getWorkUserInfoMap().get(398L).getAssignmentId());
        verify(service.assignmentMapper).insert(assignment);
    }

    @Test
    void persistsTheInternalLoginOnlyModeWithoutChangingTheLegacyTaskType() {
        var service = new TaskAssignmentService();
        service.assignmentMapper = mock(TaskAssignmentMapper.class);
        service.historyMapper = mock(TaskAssignmentHistoryMapper.class);
        service.urgentTaskService = mock(UrgentTaskService.class);
        service.dynamicInfo = new DynamicInfo();
        service.dispatchQueueService = mock(DispatchQueueService.class);
        when(service.assignmentMapper.insert(any(TaskAssignmentEntity.class))).thenReturn(1);
        var now = LocalDateTime.of(2026, 7, 28, 2, 5);
        var account = new AccountEntity().setId(7L).setTaskType("daily");

        var assignment = service.createAssignment(account, "device-1", now,
                UrgentTaskService.MODE_LOGIN_ONLY, 11L);

        assertEquals("daily", assignment.getTaskType());
        assertEquals(UrgentTaskService.MODE_LOGIN_ONLY, assignment.getTaskMode());
        assertEquals(11L, assignment.getUrgentTaskId());
    }

    @Test
    void rejectsStaleAssignmentIdsButKeepsLegacyDeviceCompatibility() {
        var service = new TaskAssignmentService();
        var assignment = new TaskAssignmentEntity()
                .setAssignmentId("assignment-current")
                .setDeviceToken("device-1");

        assertTrue(service.matchesSubmission(assignment, "device-1", "assignment-current"));
        assertFalse(service.matchesSubmission(assignment, "device-1", "assignment-old"));
        assertFalse(service.matchesSubmission(assignment, "device-2", "assignment-current"));
        assertTrue(service.matchesSubmission(assignment, "device-1", null));
        assertTrue(service.matchesSubmission(assignment, "device-1", ""));
        assertTrue(service.matchesSubmission(assignment, "device-1", "   "));
    }

    @Test
    void closingAnAssignmentArchivesItAndCanRequeueTheAccount() {
        var service = new TaskAssignmentService();
        service.assignmentMapper = mock(TaskAssignmentMapper.class);
        service.historyMapper = mock(TaskAssignmentHistoryMapper.class);
        service.urgentTaskService = mock(UrgentTaskService.class);
        service.dynamicInfo = new DynamicInfo();
        service.dispatchQueueService = mock(DispatchQueueService.class);
        when(service.assignmentMapper.deleteById("assignment-1")).thenReturn(1);
        when(service.historyMapper.insert(any(TaskAssignmentHistoryEntity.class))).thenReturn(1);

        var assignedAt = LocalDateTime.of(2026, 7, 18, 13, 0);
        var assignment = new TaskAssignmentEntity()
                .setAssignmentId("assignment-1")
                .setAccountId(398L)
                .setDeviceToken("device-1")
                .setTaskType("daily")
                .setTaskMode(UrgentTaskService.MODE_LOGIN_ONLY)
                .setUrgentTaskId(11L)
                .setAssignedAt(assignedAt)
                .setLeaseExpiresAt(assignedAt.plusHours(2))
                .setLastProgressAt(assignedAt.plusMinutes(10))
                .setGameStarted(0);
        service.dynamicInfo.addWorkUser(398L, "device-1", assignment.getLeaseExpiresAt(),
                assignment.getAssignmentId(), assignedAt, assignment.getLastProgressAt(), false);

        assertTrue(service.closeAssignment(assignment, "TIMED_OUT", "two hour limit", true));

        assertFalse(service.dynamicInfo.getWorkUserList().contains(398L));
        verify(service.dispatchQueueService).requeue(assignment);
        var historyCaptor = ArgumentCaptor.forClass(TaskAssignmentHistoryEntity.class);
        verify(service.historyMapper).insert(historyCaptor.capture());
        assertEquals(UrgentTaskService.MODE_LOGIN_ONLY, historyCaptor.getValue().getTaskMode());
        assertEquals(11L, historyCaptor.getValue().getUrgentTaskId());
        verify(service.urgentTaskService).markWaiting(eq(11L), any(LocalDateTime.class));
    }

    @Test
    void requeueingANormalAssignmentReleasesTheRunningUrgencyCreatedByTheTwoOClockSweep() {
        var service = new TaskAssignmentService();
        service.assignmentMapper = mock(TaskAssignmentMapper.class);
        service.historyMapper = mock(TaskAssignmentHistoryMapper.class);
        service.urgentTaskService = mock(UrgentTaskService.class);
        service.dynamicInfo = new DynamicInfo();
        service.dispatchQueueService = mock(DispatchQueueService.class);
        when(service.assignmentMapper.deleteById("assignment-normal")).thenReturn(1);
        when(service.historyMapper.insert(any(TaskAssignmentHistoryEntity.class))).thenReturn(1);
        var urgent = new UrgentTaskEntity().setId(12L).setAccountId(398L)
                .setStatus(UrgentTaskService.STATUS_RUNNING);
        when(service.urgentTaskService.findActiveByAccount(eq(398L), any())).thenReturn(Optional.of(urgent));
        var assignment = new TaskAssignmentEntity()
                .setAssignmentId("assignment-normal")
                .setAccountId(398L)
                .setDeviceToken("device-1")
                .setTaskType("daily")
                .setTaskMode(TaskAssignmentService.MODE_NORMAL)
                .setAssignedAt(LocalDateTime.of(2026, 7, 28, 1, 50))
                .setLeaseExpiresAt(LocalDateTime.of(2026, 7, 28, 3, 50));

        assertTrue(service.closeAssignment(assignment, "REVOKED", "device offline", true));

        verify(service.dispatchQueueService).requeue(assignment);
        verify(service.urgentTaskService).markWaiting(eq(12L), any(LocalDateTime.class));
    }

    @Test
    void aFailedDeleteDoesNotArchiveOrMutateTheQueues() {
        var service = new TaskAssignmentService();
        service.assignmentMapper = mock(TaskAssignmentMapper.class);
        service.historyMapper = mock(TaskAssignmentHistoryMapper.class);
        service.dynamicInfo = new DynamicInfo();
        service.dispatchQueueService = mock(DispatchQueueService.class);
        when(service.assignmentMapper.deleteById("assignment-1")).thenReturn(0);
        var assignment = new TaskAssignmentEntity()
                .setAssignmentId("assignment-1")
                .setAccountId(398L)
                .setDeviceToken("device-1");

        assertFalse(service.closeAssignment(assignment, "REVOKED", "offline", true));

        verify(service.historyMapper, never()).insert(any());
        assertFalse(service.dynamicInfo.getWaitUserList().contains(398L));
    }

    @Test
    void restoresPersistedActiveAssignmentsAfterAProcessRestart() {
        var service = new TaskAssignmentService();
        service.assignmentMapper = mock(TaskAssignmentMapper.class);
        service.historyMapper = mock(TaskAssignmentHistoryMapper.class);
        service.dynamicInfo = new DynamicInfo();
        service.dispatchQueueService = mock(DispatchQueueService.class);
        var assignedAt = LocalDateTime.of(2026, 7, 19, 10, 0);
        var assignment = new TaskAssignmentEntity()
                .setAssignmentId("assignment-restore")
                .setAccountId(398L)
                .setDeviceToken("device-1")
                .setAssignedAt(assignedAt)
                .setLeaseExpiresAt(assignedAt.plusHours(2))
                .setLastProgressAt(assignedAt.plusMinutes(1))
                .setGameStarted(1);
        when(service.assignmentMapper.selectList(any())).thenReturn(java.util.List.of(assignment));

        assertEquals(1, service.restoreActiveAssignments(assignedAt.plusMinutes(30)));

        var workUser = service.dynamicInfo.getWorkUserInfoMap().get(398L);
        assertNotNull(workUser);
        assertEquals("assignment-restore", workUser.getAssignmentId());
        assertTrue(workUser.getGameStarted());
    }

    @Test
    void rejectsSubmissionsAfterTheTwoHourLeaseExpires() {
        var service = new TaskAssignmentService();
        var assignment = new TaskAssignmentEntity()
                .setAssignmentId("assignment-expired")
                .setDeviceToken("device-1")
                .setLeaseExpiresAt(LocalDateTime.now().minusSeconds(1));

        assertFalse(service.matchesSubmission(assignment, "device-1", "assignment-expired"));
    }

    @Test
    void findsAndMarksTasksThatHaveReachedTheTwoHourLimit() {
        var service = new TaskAssignmentService();
        service.assignmentMapper = mock(TaskAssignmentMapper.class);
        var now = LocalDateTime.of(2026, 7, 19, 13, 0);
        var assignment = new TaskAssignmentEntity()
                .setAssignmentId("assignment-long")
                .setAssignedAt(now.minusMinutes(120))
                .setLeaseExpiresAt(now)
                .setLongTaskNotified(0);
        when(service.assignmentMapper.selectList(any())).thenReturn(java.util.List.of(assignment));
        when(service.assignmentMapper.updateById(assignment)).thenReturn(1);

        assertEquals(1, service.findLongRunning(now, 120).size());
        var queryCaptor = ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(service.assignmentMapper).selectList(queryCaptor.capture());
        var query = (com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TaskAssignmentEntity>)
                queryCaptor.getValue();
        assertEquals(7, query.getExpression().getNormal().size(),
                "long-task query must contain only assigned-at and notification-state predicates");
        assertTrue(service.markLongTaskNotified(assignment));
        assertEquals(1, assignment.getLongTaskNotified());
        verify(service.assignmentMapper).updateById(assignment);
    }

    @Test
    void expiredAssignmentRequestsLegacyDeviceShutdownAfterRecovery() {
        var service = new TaskAssignmentService();
        service.assignmentMapper = mock(TaskAssignmentMapper.class);
        service.historyMapper = mock(TaskAssignmentHistoryMapper.class);
        service.urgentTaskService = mock(UrgentTaskService.class);
        service.dynamicInfo = new DynamicInfo();
        service.dispatchQueueService = mock(DispatchQueueService.class);
        var now = LocalDateTime.of(2026, 7, 19, 13, 0);
        var assignment = new TaskAssignmentEntity()
                .setAssignmentId("assignment-expired")
                .setAccountId(398L)
                .setDeviceToken("device-1")
                .setAssignedAt(now.minusHours(2))
                .setLeaseExpiresAt(now)
                .setGameStarted(1);
        when(service.assignmentMapper.selectList(any())).thenReturn(java.util.List.of(assignment));
        when(service.assignmentMapper.deleteById("assignment-expired")).thenReturn(1);
        when(service.historyMapper.insert(any(TaskAssignmentHistoryEntity.class))).thenReturn(1);

        assertEquals(1, service.closeExpiredAssignments(now));

        assertTrue(service.dynamicInfo.getHaltList().contains("device-1"));
        verify(service.dispatchQueueService).requeue(assignment);
    }

    private static TaskAssignmentService assignmentService() {
        var service = new TaskAssignmentService();
        service.assignmentMapper = mock(TaskAssignmentMapper.class);
        service.historyMapper = mock(TaskAssignmentHistoryMapper.class);
        service.urgentTaskService = mock(UrgentTaskService.class);
        service.scheduledLifecycleService = mock(AccountScheduledRunLifecycleService.class);
        service.dispatchQueueService = mock(DispatchQueueService.class);
        service.dynamicInfo = new DynamicInfo();
        return service;
    }

    private static TaskAssignmentEntity scheduledAssignment() {
        return new TaskAssignmentEntity().setAssignmentId("scheduled-assignment")
                .setAccountId(7L).setDeviceToken("device-1").setTaskType("daily")
                .setTaskMode(TaskAssignmentService.MODE_NORMAL)
                .setDispatchSource(DispatchIntent.SOURCE_SCHEDULED)
                .setScheduledRunId(41L)
                .setAssignedAt(LocalDateTime.of(2026, 7, 28, 19, 30));
    }
}
