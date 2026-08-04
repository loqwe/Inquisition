package moe.dazecake.inquisition.service.impl;

import moe.dazecake.inquisition.mapper.LogMapper;
import moe.dazecake.inquisition.mapper.TaskAssignmentHistoryMapper;
import moe.dazecake.inquisition.model.entity.LogEntity;
import moe.dazecake.inquisition.model.entity.TaskAssignmentHistoryEntity;
import moe.dazecake.inquisition.utils.GameDayClock;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DailyLoginServiceTest {

    @Test
    void countsOnlySuccessfulLoginsInCurrentGameDay() {
        var service = new DailyLoginService();
        service.logMapper = mock(LogMapper.class);
        service.taskAssignmentHistoryMapper = mock(TaskAssignmentHistoryMapper.class);
        var now = LocalDateTime.of(2026, 7, 27, 14, 0);
        var gameDayStart = GameDayClock.startOfGameDay(now);
        when(service.logMapper.selectList(any())).thenReturn(List.of(
                loginLog(1L, "登录成功", gameDayStart).setAssignmentId("assignment-a"),
                loginLog(1L, "[07-27][11:00] 登录成功", gameDayStart.plusHours(1)).setAssignmentId("assignment-b"),
                loginLog(1L, "开始登录", gameDayStart.plusHours(2)).setAssignmentId("assignment-c"),
                loginLog(1L, "登录成功", gameDayStart.minusNanos(1)).setAssignmentId("assignment-old"),
                loginLog(2L, "登录成功", gameDayStart.plusHours(1)).setLevel("WARN"),
                loginLog(2L, "登录成功", gameDayStart.plusHours(1)).setFrom("SYSTEM"),
                loginLog(2L, "登录成功", gameDayStart.plusHours(1)).setDelete(1)
        ));
        when(service.taskAssignmentHistoryMapper.selectList(any())).thenReturn(List.of());

        var counts = service.getLoginCounts(Set.of(1L, 2L), now);

        assertEquals(2, counts.get(1L));
        assertEquals(0, counts.getOrDefault(2L, 0));
        verify(service.logMapper, times(1)).selectList(any());
    }

    @Test
    void countsEachAssignmentOnlyOnce() {
        var service = new DailyLoginService();
        service.logMapper = mock(LogMapper.class);
        service.taskAssignmentHistoryMapper = mock(TaskAssignmentHistoryMapper.class);
        var now = LocalDateTime.of(2026, 7, 27, 14, 0);
        var gameDayStart = GameDayClock.startOfGameDay(now);
        when(service.logMapper.selectList(any())).thenReturn(List.of(
                loginLog(91L, "登录成功", gameDayStart.plusMinutes(19)).setAssignmentId("assignment-a"),
                loginLog(91L, "登录成功", gameDayStart.plusMinutes(19).plusSeconds(9)).setAssignmentId("assignment-a"),
                loginLog(91L, "登录成功", gameDayStart.plusHours(4)).setAssignmentId("assignment-b"),
                loginLog(91L, "登录成功", gameDayStart.plusHours(4).plusSeconds(9)).setAssignmentId("assignment-b"),
                loginLog(91L, "登录成功", gameDayStart.plusHours(4).plusMinutes(1)).setAssignmentId("assignment-b")
        ));
        when(service.taskAssignmentHistoryMapper.selectList(any())).thenReturn(List.of());

        var counts = service.getLoginCounts(Set.of(91L), now);

        assertEquals(2, counts.get(91L));
    }

    @Test
    void countsCompletedFullDailyAssignmentWithoutLoginLog() {
        var service = new DailyLoginService();
        service.logMapper = mock(LogMapper.class);
        service.taskAssignmentHistoryMapper = mock(TaskAssignmentHistoryMapper.class);
        var now = LocalDateTime.of(2026, 7, 27, 14, 0);
        var gameDayStart = GameDayClock.startOfGameDay(now);
        when(service.logMapper.selectList(any())).thenReturn(List.of());
        when(service.taskAssignmentHistoryMapper.selectList(any())).thenReturn(List.of(
                completedDaily(91L, "assignment-a", gameDayStart.plusHours(2)),
                completedDaily(91L, "assignment-login-only", gameDayStart.plusHours(2))
                        .setTaskMode("LOGIN_ONLY"),
                completedDaily(91L, "assignment-rogue", gameDayStart.plusHours(2))
                        .setTaskType("rogue"),
                completedDaily(91L, "assignment-failed", gameDayStart.plusHours(2))
                        .setStatus("FAILED"),
                completedDaily(91L, "assignment-old", gameDayStart.minusNanos(1))
        ));

        var counts = service.getLoginCounts(Set.of(91L), now);

        assertEquals(1, counts.get(91L));
    }

    @Test
    void doesNotDoubleCountLoginLogAndCompletionForSameAssignment() {
        var service = new DailyLoginService();
        service.logMapper = mock(LogMapper.class);
        service.taskAssignmentHistoryMapper = mock(TaskAssignmentHistoryMapper.class);
        var now = LocalDateTime.of(2026, 7, 27, 14, 0);
        var gameDayStart = GameDayClock.startOfGameDay(now);
        when(service.logMapper.selectList(any())).thenReturn(List.of(
                loginLog(91L, "登录成功", gameDayStart.plusMinutes(10))
                        .setAssignmentId("assignment-a")
        ));
        when(service.taskAssignmentHistoryMapper.selectList(any())).thenReturn(List.of(
                completedDaily(91L, "assignment-a", gameDayStart.plusHours(1))
        ));

        var counts = service.getLoginCounts(Set.of(91L), now);

        assertEquals(1, counts.get(91L));
    }

    private static LogEntity loginLog(Long accountId, String title, LocalDateTime time) {
        return new LogEntity()
                .setAccountId(accountId)
                .setTitle(title)
                .setLevel("INFO")
                .setFrom("device-token")
                .setDelete(0)
                .setTime(time);
    }

    private static TaskAssignmentHistoryEntity completedDaily(Long accountId, String assignmentId,
                                                               LocalDateTime finishedAt) {
        return new TaskAssignmentHistoryEntity()
                .setAccountId(accountId)
                .setAssignmentId(assignmentId)
                .setTaskType("daily")
                .setTaskMode("NORMAL")
                .setStatus("COMPLETED")
                .setFinishedAt(finishedAt);
    }
}
