package moe.dazecake.inquisition.service.impl;

import moe.dazecake.inquisition.mapper.TaskAssignmentHistoryMapper;
import moe.dazecake.inquisition.mapper.TaskAssignmentMapper;
import moe.dazecake.inquisition.model.entity.AccountEntity;
import moe.dazecake.inquisition.model.entity.TaskAssignmentEntity;
import moe.dazecake.inquisition.model.entity.TaskAssignmentHistoryEntity;
import moe.dazecake.inquisition.utils.DynamicInfo;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskAssignmentServiceTest {

    @Test
    void createsATwoHourHardLeaseAndMirrorsItInMemory() {
        var service = new TaskAssignmentService();
        service.assignmentMapper = mock(TaskAssignmentMapper.class);
        service.historyMapper = mock(TaskAssignmentHistoryMapper.class);
        service.dynamicInfo = new DynamicInfo();
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
        service.dynamicInfo = new DynamicInfo();
        when(service.assignmentMapper.deleteById("assignment-1")).thenReturn(1);
        when(service.historyMapper.insert(any(TaskAssignmentHistoryEntity.class))).thenReturn(1);

        var assignedAt = LocalDateTime.of(2026, 7, 18, 13, 0);
        var assignment = new TaskAssignmentEntity()
                .setAssignmentId("assignment-1")
                .setAccountId(398L)
                .setDeviceToken("device-1")
                .setTaskType("daily")
                .setAssignedAt(assignedAt)
                .setLeaseExpiresAt(assignedAt.plusHours(2))
                .setLastProgressAt(assignedAt.plusMinutes(10))
                .setGameStarted(0);
        service.dynamicInfo.addWorkUser(398L, "device-1", assignment.getLeaseExpiresAt(),
                assignment.getAssignmentId(), assignedAt, assignment.getLastProgressAt(), false);

        assertTrue(service.closeAssignment(assignment, "TIMED_OUT", "two hour limit", true));

        assertFalse(service.dynamicInfo.getWorkUserList().contains(398L));
        assertTrue(service.dynamicInfo.getWaitUserList().contains(398L));
        verify(service.historyMapper).insert(any(TaskAssignmentHistoryEntity.class));
    }

    @Test
    void aFailedDeleteDoesNotArchiveOrMutateTheQueues() {
        var service = new TaskAssignmentService();
        service.assignmentMapper = mock(TaskAssignmentMapper.class);
        service.historyMapper = mock(TaskAssignmentHistoryMapper.class);
        service.dynamicInfo = new DynamicInfo();
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
        service.dynamicInfo = new DynamicInfo();
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
        assertTrue(service.dynamicInfo.getWaitUserList().contains(398L));
    }
}
