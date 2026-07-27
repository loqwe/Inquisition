package moe.dazecake.inquisition.service.impl;

import moe.dazecake.inquisition.mapper.UrgentTaskMapper;
import moe.dazecake.inquisition.model.entity.UrgentTaskEntity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UrgentTaskServiceTest {

    @Test
    void createsOneWaitingLoginOnlyTaskForAnAccountAndGameDay() {
        var service = service();
        var now = LocalDateTime.of(2026, 7, 27, 14, 0);
        when(service.urgentTaskMapper.selectOne(any())).thenReturn(null);
        when(service.urgentTaskMapper.insert(any())).thenAnswer(invocation -> {
            invocation.<UrgentTaskEntity>getArgument(0).setId(11L);
            return 1;
        });

        var task = service.upsert(7L, LocalDate.of(2026, 7, 27),
                UrgentTaskService.TRIGGER_TWENTY_SIX, UrgentTaskService.MODE_LOGIN_ONLY,
                UrgentTaskService.PRIORITY_TWENTY_SIX, UrgentTaskService.STATUS_WAITING, null, now);

        assertEquals(11L, task.getId());
        assertEquals(UrgentTaskService.MODE_LOGIN_ONLY, task.getTaskMode());
        assertEquals(UrgentTaskService.STATUS_WAITING, task.getStatus());
        assertEquals(0, task.getAttemptCount());
        assertEquals(now, task.getCreatedAt());
        assertEquals(now, task.getUpdatedAt());
    }

    @Test
    void repeatedTwentySixSweepRefreshesTheExistingTaskWithoutResettingAttempts() {
        var service = service();
        var gameDay = LocalDate.of(2026, 7, 27);
        var createdAt = LocalDateTime.of(2026, 7, 27, 14, 0);
        var now = LocalDateTime.of(2026, 7, 28, 2, 0);
        var existing = task(11L, 7L, gameDay, UrgentTaskService.PRIORITY_TWENTY_SIX, createdAt)
                .setAttemptCount(2)
                .setStatus(UrgentTaskService.STATUS_RUNNING)
                .setTaskMode(UrgentTaskService.MODE_LOGIN_ONLY)
                .setTriggerType(UrgentTaskService.TRIGGER_TWENTY_SIX);
        when(service.urgentTaskMapper.selectOne(any())).thenReturn(existing);
        when(service.urgentTaskMapper.updateById(any())).thenReturn(1);

        var upgraded = service.upsert(7L, gameDay,
                UrgentTaskService.TRIGGER_TWENTY_SIX, UrgentTaskService.MODE_LOGIN_ONLY,
                UrgentTaskService.PRIORITY_TWENTY_SIX, UrgentTaskService.STATUS_RUNNING, null, now);

        assertEquals(11L, upgraded.getId());
        assertEquals(UrgentTaskService.MODE_LOGIN_ONLY, upgraded.getTaskMode());
        assertEquals(UrgentTaskService.TRIGGER_TWENTY_SIX, upgraded.getTriggerType());
        assertEquals(UrgentTaskService.PRIORITY_TWENTY_SIX, upgraded.getPriority());
        assertEquals(UrgentTaskService.STATUS_RUNNING, upgraded.getStatus());
        assertEquals(2, upgraded.getAttemptCount());
        assertEquals(createdAt, upgraded.getCreatedAt());
        assertEquals(now, upgraded.getUpdatedAt());
    }

    @Test
    void dispatchableTasksAreReadyAndSortedByPriorityThenAge() {
        var service = service();
        var gameDay = LocalDate.of(2026, 7, 27);
        var now = LocalDateTime.of(2026, 7, 28, 2, 10);
        var oldestFinal = task(1L, 1L, gameDay, 100, now.minusHours(5));
        var newestFinal = task(2L, 2L, gameDay, 100, now.minusMinutes(5));
        var middleFinal = task(3L, 3L, gameDay, 100, now.minusMinutes(10));
        var futureRetry = task(4L, 4L, gameDay, 100, now.minusMinutes(20))
                .setStatus(UrgentTaskService.STATUS_RETRY_WAIT)
                .setNextRetryAt(now.plusMinutes(1));
        var running = task(5L, 5L, gameDay, 100, now.minusMinutes(30))
                .setStatus(UrgentTaskService.STATUS_RUNNING);
        var completed = task(6L, 6L, gameDay, 100, now.minusMinutes(40))
                .setStatus(UrgentTaskService.STATUS_SUCCEEDED);
        when(service.urgentTaskMapper.selectList(any())).thenReturn(
                List.of(newestFinal, middleFinal, oldestFinal, futureRetry, running, completed));

        var result = service.findDispatchable(gameDay, now);

        assertEquals(List.of(1L, 3L, 2L), result.stream()
                .map(UrgentTaskEntity::getId)
                .collect(Collectors.toList()));
    }

    @Test
    void runningRetryAndSuccessfulLoginTransitionsRetainOnePersistentRecord() {
        var service = service();
        var gameDay = LocalDate.of(2026, 7, 27);
        var now = LocalDateTime.of(2026, 7, 28, 2, 10);
        var task = task(11L, 7L, gameDay, 100, now.minusMinutes(10));
        when(service.urgentTaskMapper.updateById(any())).thenReturn(1);

        service.markRunning(task, now);
        assertEquals(UrgentTaskService.STATUS_RUNNING, task.getStatus());
        assertEquals(1, task.getAttemptCount());

        var retryAt = now.plusMinutes(10);
        service.markRetry(task, "network", retryAt, now.plusMinutes(1));
        assertEquals(UrgentTaskService.STATUS_RETRY_WAIT, task.getStatus());
        assertEquals(retryAt, task.getNextRetryAt());
        assertEquals("network", task.getLastError());

        when(service.urgentTaskMapper.selectOne(any())).thenReturn(task);
        var completed = service.completeForSuccessfulLogin(7L, gameDay, now.plusMinutes(2));
        assertTrue(completed.isPresent());
        assertEquals(UrgentTaskService.STATUS_SUCCEEDED, completed.get().getStatus());
        assertEquals(null, completed.get().getNextRetryAt());
        assertEquals(null, completed.get().getLastError());
    }

    @Test
    void retryNowAndCancelOnlyChangeUrgencyState() {
        var service = service();
        var now = LocalDateTime.of(2026, 7, 28, 2, 20);
        var task = task(11L, 7L, LocalDate.of(2026, 7, 27), 100, now.minusMinutes(10))
                .setStatus(UrgentTaskService.STATUS_RETRY_WAIT)
                .setNextRetryAt(now.plusHours(1))
                .setLastError("network");
        when(service.urgentTaskMapper.selectById(11L)).thenReturn(task);
        when(service.urgentTaskMapper.updateById(any())).thenReturn(1);

        assertTrue(service.retryNow(11L, now));
        assertEquals(UrgentTaskService.STATUS_WAITING, task.getStatus());
        assertEquals(null, task.getNextRetryAt());
        assertEquals(null, task.getLastError());

        assertTrue(service.cancel(11L, now.plusMinutes(1)));
        assertEquals(UrgentTaskService.STATUS_CANCELLED, task.getStatus());
        assertFalse(UrgentTaskService.isActiveStatus(task.getStatus()));
    }

    @Test
    void retryNowRejectsAnUrgencyThatIsAlreadyWaitingOrRunning() {
        var service = service();
        var now = LocalDateTime.of(2026, 7, 28, 2, 20);
        var task = task(11L, 7L, LocalDate.of(2026, 7, 27), 100, now.minusMinutes(10))
                .setStatus(UrgentTaskService.STATUS_RUNNING);
        when(service.urgentTaskMapper.selectById(11L)).thenReturn(task);

        assertFalse(service.retryNow(11L, now));
        verify(service.urgentTaskMapper, never()).updateById(any());
    }

    @Test
    void requeuedAssignmentReturnsToWaitingWithoutResettingAttemptCount() {
        var service = service();
        var now = LocalDateTime.of(2026, 7, 28, 2, 30);
        var task = task(11L, 7L, LocalDate.of(2026, 7, 27), 100, now.minusMinutes(20))
                .setStatus(UrgentTaskService.STATUS_RUNNING)
                .setAttemptCount(3);
        when(service.urgentTaskMapper.selectById(11L)).thenReturn(task);
        when(service.urgentTaskMapper.updateById(any())).thenReturn(1);

        assertTrue(service.markWaiting(11L, now));

        assertEquals(UrgentTaskService.STATUS_WAITING, task.getStatus());
        assertEquals(3, task.getAttemptCount());
        assertEquals(null, task.getNextRetryAt());
    }

    @Test
    void cleanupDeletesOnlyRowsBeforeTheNewGameDay() {
        var service = service();
        when(service.urgentTaskMapper.delete(any())).thenReturn(4);

        assertEquals(4, service.cleanupBefore(LocalDate.of(2026, 7, 28)));
        verify(service.urgentTaskMapper).delete(any());
    }

    private static UrgentTaskService service() {
        var service = new UrgentTaskService();
        service.urgentTaskMapper = mock(UrgentTaskMapper.class);
        return service;
    }

    private static UrgentTaskEntity task(Long id, Long accountId, LocalDate gameDay,
                                         int priority, LocalDateTime createdAt) {
        return new UrgentTaskEntity()
                .setId(id)
                .setAccountId(accountId)
                .setGameDay(gameDay)
                .setTriggerType(UrgentTaskService.TRIGGER_TWENTY_SIX)
                .setTaskMode(UrgentTaskService.MODE_LOGIN_ONLY)
                .setPriority(priority)
                .setStatus(UrgentTaskService.STATUS_WAITING)
                .setAttemptCount(0)
                .setCreatedAt(createdAt)
                .setUpdatedAt(createdAt);
    }
}
