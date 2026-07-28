package moe.dazecake.inquisition.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import moe.dazecake.inquisition.mapper.AccountMapper;
import moe.dazecake.inquisition.mapper.LogMapper;
import moe.dazecake.inquisition.model.dto.account.AccountDTO;
import moe.dazecake.inquisition.model.entity.AccountEntity;
import moe.dazecake.inquisition.model.entity.ConfigEntitySet.ConfigEntity;
import moe.dazecake.inquisition.model.entity.ConfigEntitySet.Fight;
import moe.dazecake.inquisition.model.entity.LogEntity;
import moe.dazecake.inquisition.utils.DynamicInfo;
import moe.dazecake.inquisition.utils.GameDayClock;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountServiceImplTest {

    @Test
    void deleteAccountHardDeletesAndClearsRuntimeState() {
        var service = new AccountServiceImpl();
        service.accountMapper = mock(AccountMapper.class);
        service.dynamicInfo = new DynamicInfo();
        service.taskService = mock(TaskServiceImpl.class);

        service.dynamicInfo.getWaitUserList().add(423L);
        service.dynamicInfo.getFreezeUserInfoMap().put(423L, java.time.LocalDateTime.now().plusHours(1));
        service.dynamicInfo.getUserSanInfoMap().put(423L, new moe.dazecake.inquisition.model.local.UserSan(1, 135));

        service.deleteAccount(423L);

        verify(service.taskService).forceHaltTask(423L);
        verify(service.accountMapper).hardDeleteById(423L);
        verify(service.accountMapper, never()).updateById(org.mockito.ArgumentMatchers.any());
        org.junit.jupiter.api.Assertions.assertFalse(service.dynamicInfo.getUserSanInfoMap().containsKey(423L));
    }

    @Test
    void queryAccountKeepsFuzzyMatchesAfterExactMatch() {
        var service = new AccountServiceImpl();
        service.accountMapper = mock(AccountMapper.class);
        service.dynamicInfo = new DynamicInfo();
        var logMapper = mock(LogMapper.class);
        service.dailyLoginService = dailyLoginService(logMapper);

        var searchPage = new Page<AccountEntity>(1, 10);
        searchPage.setRecords(List.of(
                new AccountEntity().setId(1L).setName("账号1").setAccount("16603003649"),
                new AccountEntity().setId(10L).setName("账号10").setAccount("16603003650")
        ));
        searchPage.setTotal(2);

        when(service.accountMapper.searchActiveExactFirst(any(Page.class), eq("账号1"), isNull())).thenReturn(searchPage);
        when(logMapper.selectList(any())).thenReturn(List.of());

        var result = service.queryAccount(1L, 10L, "账号1");

        assertEquals(2, result.getTotal());
        assertEquals("账号1", result.getRecords().get(0).getName());
        assertEquals("账号10", result.getRecords().get(1).getName());
        verify(service.accountMapper).searchActiveExactFirst(any(Page.class), eq("账号1"), isNull());
    }

    @Test
    void accountListCountsOnlySuccessfulLoginsInCurrentGameDay() {
        var service = new AccountServiceImpl();
        service.accountMapper = mock(AccountMapper.class);
        service.dynamicInfo = new DynamicInfo();
        var logMapper = mock(LogMapper.class);
        service.dailyLoginService = dailyLoginService(logMapper);

        var page = new Page<AccountEntity>(1, 10);
        page.setRecords(List.of(
                new AccountEntity().setId(1L).setName("账号1").setAccount("account-1"),
                new AccountEntity().setId(2L).setName("账号2").setAccount("account-2")
        ));
        page.setTotal(2);

        var gameDayStart = GameDayClock.startOfGameDay(GameDayClock.now());
        when(logMapper.selectList(any())).thenReturn(List.of(
                loginLog(1L, "登录成功", gameDayStart),
                loginLog(1L, "[07-27][11:00] 登录成功", gameDayStart.plusHours(1)),
                loginLog(1L, "开始登录", gameDayStart.plusHours(2)),
                loginLog(1L, "通知", gameDayStart.plusHours(3)),
                loginLog(1L, "登录成功", gameDayStart.minusNanos(1)),
                loginLog(2L, "登录成功", gameDayStart.plusHours(1)).setLevel("WARN"),
                loginLog(2L, "登录成功", gameDayStart.plusHours(1)).setFrom("SYSTEM"),
                loginLog(2L, "登录成功", gameDayStart.plusHours(1)).setDelete(1)
        ));

        var result = service.getAccountWithSanVOPageQueryVO(page);

        assertEquals(2, result.getRecords().get(0).getTodayLoginCount());
        assertEquals(0, result.getRecords().get(1).getTodayLoginCount());
        verify(logMapper, times(1)).selectList(any());
    }

    @Test
    void accountListCountsEachTaskAssignmentOnlyOnce() {
        var service = new AccountServiceImpl();
        service.accountMapper = mock(AccountMapper.class);
        service.dynamicInfo = new DynamicInfo();
        var logMapper = mock(LogMapper.class);
        service.dailyLoginService = dailyLoginService(logMapper);

        var page = new Page<AccountEntity>(1, 10);
        page.setRecords(List.of(
                new AccountEntity().setId(91L).setName("账号749").setAccount("account-749")
        ));
        page.setTotal(1);

        var gameDayStart = GameDayClock.startOfGameDay(GameDayClock.now());
        when(logMapper.selectList(any())).thenReturn(List.of(
                loginLog(91L, "登录成功", gameDayStart.plusMinutes(19)).setAssignmentId("assignment-a"),
                loginLog(91L, "登录成功", gameDayStart.plusMinutes(19).plusSeconds(9)).setAssignmentId("assignment-a"),
                loginLog(91L, "登录成功", gameDayStart.plusHours(4)).setAssignmentId("assignment-b"),
                loginLog(91L, "登录成功", gameDayStart.plusHours(4).plusSeconds(9)).setAssignmentId("assignment-b"),
                loginLog(91L, "登录成功", gameDayStart.plusHours(4).plusMinutes(1)).setAssignmentId("assignment-b")
        ));

        var result = service.getAccountWithSanVOPageQueryVO(page);

        assertEquals(2, result.getRecords().get(0).getTodayLoginCount());
    }

    @Test
    void updateAccountPartialPayloadDoesNotResetConfigDefaults() {
        var service = new AccountServiceImpl();
        service.accountMapper = mock(AccountMapper.class);

        var customConfig = new ConfigEntity();
        customConfig.getDaily().setMail(false);
        customConfig.getDaily().setFight(List.of(new Fight("custom-stage", 3)));

        var existing = new AccountEntity()
                .setId(1L)
                .setName("账号1")
                .setFreeze(1)
                .setConfig(customConfig);
        when(service.accountMapper.selectById(1L)).thenReturn(existing);

        var partialUpdate = new AccountDTO();
        partialUpdate.setId(1L);
        partialUpdate.setFreeze(0);

        service.updateAccount(partialUpdate, Set.of("id", "freeze"));

        var captor = ArgumentCaptor.forClass(AccountEntity.class);
        verify(service.accountMapper).updateById(captor.capture());
        assertEquals(0, captor.getValue().getFreeze());
        assertSame(customConfig, captor.getValue().getConfig());
        assertFalse(captor.getValue().getConfig().getDaily().isMail());
        assertEquals("custom-stage", captor.getValue().getConfig().getDaily().getFight().get(0).getLevel());
    }

    @Test
    void immediateStartUsesTheManualQueueIntentWithoutEditingDeviceData() {
        var service = new AccountServiceImpl();
        service.accountMapper = mock(AccountMapper.class);
        service.dynamicInfo = new DynamicInfo();
        service.dispatchQueueService = mock(DispatchQueueService.class);
        var account = new AccountEntity().setId(1L).setDelete(0).setFreeze(0)
                .setRefresh(1).setExpireTime(LocalDateTime.of(2099, 1, 1, 0, 0));
        when(service.accountMapper.selectById(1L)).thenReturn(account);
        when(service.dispatchQueueService.enqueueManual(1L)).thenReturn(true);

        assertEquals("立即开始作战成功，等待分配作战服务器",
                service.forceFightAccount(1L, true));

        verify(service.dispatchQueueService).enqueueManual(1L);
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

    private static DailyLoginService dailyLoginService(LogMapper logMapper) {
        var service = new DailyLoginService();
        service.logMapper = logMapper;
        return service;
    }
}
