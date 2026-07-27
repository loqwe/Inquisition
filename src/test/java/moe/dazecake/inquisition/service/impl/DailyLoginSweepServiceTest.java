package moe.dazecake.inquisition.service.impl;

import moe.dazecake.inquisition.mapper.AccountMapper;
import moe.dazecake.inquisition.mapper.LogMapper;
import moe.dazecake.inquisition.model.entity.AccountEntity;
import moe.dazecake.inquisition.model.entity.TaskAssignmentEntity;
import moe.dazecake.inquisition.utils.DynamicInfo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DailyLoginSweepServiceTest {

    @Test
    void prioritizesOnlyEligibleMissingAccountsWithoutMutatingAccountRows() {
        var service = serviceWithMocks();
        var now = LocalDateTime.of(2026, 7, 27, 14, 0);
        when(service.logMapper.selectCount(any())).thenReturn(0L);
        when(service.accountMapper.selectList(any())).thenReturn(List.of(
                account(1L, "账号1", now.plusDays(1)),
                account(2L, "账号2", now.plusDays(1)),
                account(3L, "账号3", now.plusDays(1)),
                account(4L, "账号4", now.plusDays(1)),
                account(5L, "账号5", now.plusDays(1)),
                account(6L, "冻结", now.plusDays(1)).setFreeze(1),
                account(7L, "过期", now.minusSeconds(1)),
                account(8L, "肉鸽", now.plusDays(1)).setTaskType("rogue"),
                account(9L, "删除", now.plusDays(1)).setDelete(1)
        ));
        when(service.dailyLoginService.getLoginCounts(any(), eq(now))).thenReturn(Map.of(2L, 1));
        when(service.taskAssignmentService.findAll()).thenReturn(List.of(
                new TaskAssignmentEntity().setAssignmentId("running").setAccountId(4L)
        ));
        service.dynamicInfo.setWaitUserList(new java.util.ArrayList<>(List.of(99L, 3L, 5L)));
        service.dynamicInfo.getFreezeUserInfoMap().put(5L, now.plusMinutes(30));

        var result = service.runIfDue(now);

        assertTrue(result.isExecuted());
        assertEquals(5, result.getEligibleCount());
        assertEquals(4, result.getMissingCount());
        assertEquals(2, result.getPrioritizedCount());
        assertEquals(1, result.getRunningCount());
        assertEquals(1, result.getCooldownCount());
        assertEquals(List.of(1L, 3L, 99L, 5L), service.dynamicInfo.getWaitUserList());
        verify(service.accountMapper, never()).updateById(any());
        verify(service.messageService).pushAdmin(contains("14点补登"), contains("账号1"));
        verify(service.logService).logInfo(eq(DailyLoginSweepService.JOB_LOG_TITLE), contains("优先入队: 2"));

        var secondRun = service.runIfDue(now.plusMinutes(5));

        assertFalse(secondRun.isExecuted());
        assertEquals(List.of(1L, 3L, 99L, 5L), service.dynamicInfo.getWaitUserList());
        verify(service.messageService, times(1)).pushAdmin(any(), any());
    }

    @Test
    void skipsBeforeFourteenAndUsesPersistentMarkerAfterRestart() {
        var beforeFourteen = serviceWithMocks();
        var earlyResult = beforeFourteen.runIfDue(LocalDateTime.of(2026, 7, 27, 13, 59));
        assertFalse(earlyResult.isExecuted());
        verify(beforeFourteen.logMapper, never()).selectCount(any());

        var restarted = serviceWithMocks();
        when(restarted.logMapper.selectCount(any())).thenReturn(1L);
        var restartedResult = restarted.runIfDue(LocalDateTime.of(2026, 7, 27, 15, 0));
        assertFalse(restartedResult.isExecuted());
        verify(restarted.accountMapper, never()).selectList(any());
    }

    @Test
    void catchesUpBeforeResetForThePreviousGameDay() {
        var service = serviceWithMocks();
        var now = LocalDateTime.of(2026, 7, 28, 2, 0);
        when(service.logMapper.selectCount(any())).thenReturn(0L);
        when(service.accountMapper.selectList(any())).thenReturn(List.of());

        var result = service.runIfDue(now);

        assertTrue(result.isExecuted());
        assertEquals(LocalDate.of(2026, 7, 27), result.getGameDay());
        verify(service.logService).logInfo(eq(DailyLoginSweepService.JOB_LOG_TITLE), any());
    }

    @Test
    void persistedJobSummaryFitsTheLogDetailColumn() {
        var service = serviceWithMocks();
        var now = LocalDateTime.of(2026, 7, 27, 14, 0);
        when(service.logMapper.selectCount(any())).thenReturn(0L);
        when(service.accountMapper.selectList(any())).thenReturn(LongStream.rangeClosed(1, 70)
                .mapToObj(id -> account(id, "需要补登的长账号名称" + id, now.plusDays(1)))
                .collect(Collectors.toList()));

        service.runIfDue(now);

        var detailCaptor = ArgumentCaptor.forClass(String.class);
        verify(service.logService).logInfo(eq(DailyLoginSweepService.JOB_LOG_TITLE), detailCaptor.capture());
        assertTrue(detailCaptor.getValue().length() <= 255);
    }

    private static DailyLoginSweepService serviceWithMocks() {
        var service = new DailyLoginSweepService();
        service.accountMapper = mock(AccountMapper.class);
        service.logMapper = mock(LogMapper.class);
        service.dailyLoginService = mock(DailyLoginService.class);
        service.taskAssignmentService = mock(TaskAssignmentService.class);
        service.messageService = mock(MessageServiceImpl.class);
        service.logService = mock(LogServiceImpl.class);
        service.dynamicInfo = new DynamicInfo();
        when(service.taskAssignmentService.findAll()).thenReturn(List.of());
        return service;
    }

    private static AccountEntity account(Long id, String name, LocalDateTime expireTime) {
        return new AccountEntity()
                .setId(id)
                .setName(name)
                .setAccount("account-" + id)
                .setTaskType("daily")
                .setFreeze(0)
                .setDelete(0)
                .setRefresh(1)
                .setExpireTime(expireTime);
    }
}
