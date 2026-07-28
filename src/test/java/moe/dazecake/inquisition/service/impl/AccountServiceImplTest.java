package moe.dazecake.inquisition.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import moe.dazecake.inquisition.mapper.AccountDispatchConfigMapper;
import moe.dazecake.inquisition.mapper.AccountMapper;
import moe.dazecake.inquisition.mapper.AccountScheduledRunMapper;
import moe.dazecake.inquisition.mapper.LogMapper;
import moe.dazecake.inquisition.model.dto.account.AccountDispatchConfigDTO;
import moe.dazecake.inquisition.model.dto.account.AccountDTO;
import moe.dazecake.inquisition.model.entity.AccountDispatchConfigEntity;
import moe.dazecake.inquisition.model.entity.AccountEntity;
import moe.dazecake.inquisition.model.entity.AccountScheduledRunEntity;
import moe.dazecake.inquisition.model.entity.ActivationDateSet.ActivationDate;
import moe.dazecake.inquisition.model.entity.ConfigEntitySet.ConfigEntity;
import moe.dazecake.inquisition.model.entity.ConfigEntitySet.Fight;
import moe.dazecake.inquisition.model.entity.LogEntity;
import moe.dazecake.inquisition.model.entity.TaskAssignmentEntity;
import moe.dazecake.inquisition.model.local.DispatchIntent;
import moe.dazecake.inquisition.utils.DynamicInfo;
import moe.dazecake.inquisition.utils.GameDayClock;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
        service.dispatchQueueService = mock(DispatchQueueService.class);
        service.dispatchConfigMapper = mock(AccountDispatchConfigMapper.class);
        service.scheduledRunService = mock(AccountScheduledRunService.class);
        when(service.scheduledRunService.findActiveByAccount(423L)).thenReturn(Optional.of(
                new AccountScheduledRunEntity().setId(41L).setAccountId(423L)
                        .setStatus(AccountScheduledRunService.STATUS_WAITING)));
        when(service.scheduledRunService.cancel(41L)).thenReturn(true);

        service.dynamicInfo.getWaitUserList().add(423L);
        service.dynamicInfo.getFreezeUserInfoMap().put(423L, java.time.LocalDateTime.now().plusHours(1));
        service.dynamicInfo.getUserSanInfoMap().put(423L, new moe.dazecake.inquisition.model.local.UserSan(1, 135));

        service.deleteAccount(423L);

        verify(service.taskService).forceHaltTask(423L);
        verify(service.scheduledRunService).cancel(41L);
        verify(service.dispatchQueueService).remove(423L);
        verify(service.dispatchConfigMapper).deleteById(423L);
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
        initializeDispatchHydration(service);

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
        initializeDispatchHydration(service);

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
        initializeDispatchHydration(service);

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
    void missingLoginFilterUsesDatabasePageBeforeHydration() {
        var service = new AccountServiceImpl();
        service.accountMapper = mock(AccountMapper.class);
        service.dynamicInfo = new DynamicInfo();
        service.dailyLoginService = dailyLoginService(mock(LogMapper.class));
        initializeDispatchHydration(service);
        var now = LocalDateTime.of(2026, 7, 29, 10, 0);
        var gameDayStart = GameDayClock.startOfGameDay(now);
        var page = new Page<AccountEntity>(1, 10);
        page.setRecords(List.of(new AccountEntity().setId(7L).setName("账号7")
                .setAccount("account-7")));
        page.setTotal(1);
        when(service.accountMapper.selectMissingDailyLoginPage(any(Page.class), eq(now), eq(gameDayStart)))
                .thenReturn(page);

        var result = service.queryAllAccount(1L, 10L, null, null, null, null,
                "missing", now);

        assertEquals(1, result.getTotal());
        assertEquals(7L, result.getRecords().get(0).getId());
        verify(service.accountMapper).selectMissingDailyLoginPage(any(Page.class), eq(now), eq(gameDayStart));
    }

    @Test
    void updateAccountPartialPayloadDoesNotResetConfigDefaults() {
        var service = new AccountServiceImpl();
        service.accountMapper = mock(AccountMapper.class);
        service.dispatchConfigService = mock(AccountDispatchConfigService.class);
        service.taskAssignmentService = mock(TaskAssignmentService.class);
        service.scheduledRunService = mock(AccountScheduledRunService.class);
        when(service.dispatchConfigService.getOrDefault(1L)).thenReturn(
                new AccountDispatchConfigEntity().setAccountId(1L)
                        .setDispatchMode(AccountDispatchConfigService.AUTO));
        when(service.taskAssignmentService.findByAccount(1L)).thenReturn(Optional.empty());
        when(service.scheduledRunService.findActiveByAccount(1L)).thenReturn(Optional.empty());

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

    @Test
    void administratorUpdatePersistsActiveWeekAndScheduledConfigInOneTransaction()
            throws Exception {
        var service = dispatchUpdateService();
        var existing = activeAccount(1L);
        existing.getActive().getMonday().setEnable(false);
        when(service.accountMapper.selectById(1L)).thenReturn(existing);
        when(service.taskAssignmentService.findByAccount(1L)).thenReturn(Optional.empty());
        when(service.scheduledRunService.findActiveByAccount(1L)).thenReturn(Optional.empty());
        when(service.dispatchConfigService.getOrDefault(1L)).thenReturn(
                new AccountDispatchConfigEntity().setAccountId(1L).setDispatchMode("AUTO"));
        var update = new AccountDTO();
        update.setId(1L);
        var active = new ActivationDate();
        active.getMonday().setEnable(true);
        update.setActive(active);
        var dispatchConfig = scheduledRequest(LocalTime.of(19, 30));

        service.updateAccount(update, Set.of("id", "active", "dispatchConfig"), dispatchConfig);

        assertTrue(existing.getActive().getMonday().isEnable());
        verify(service.accountMapper).updateById(existing);
        verify(service.dispatchConfigService).update(
                eq(existing), eq(dispatchConfig), eq(false), any(LocalDateTime.class));
        verify(service.dispatchQueueService).remove(1L);
        assertNotNull(AccountServiceImpl.class.getMethod("updateAccount",
                        AccountDTO.class, Set.class, AccountDispatchConfigDTO.class)
                .getAnnotation(Transactional.class));
    }

    @Test
    void activeAssignmentDefersNewScheduleWithoutChangingTheCurrentLease() {
        var service = dispatchUpdateService();
        var existing = activeAccount(1L);
        when(service.accountMapper.selectById(1L)).thenReturn(existing);
        when(service.taskAssignmentService.findByAccount(1L)).thenReturn(Optional.of(
                new TaskAssignmentEntity().setAccountId(1L)
                        .setDispatchSource(DispatchIntent.SOURCE_AUTO)));
        when(service.scheduledRunService.findActiveByAccount(1L)).thenReturn(Optional.empty());
        when(service.dispatchConfigService.getOrDefault(1L)).thenReturn(
                new AccountDispatchConfigEntity().setAccountId(1L).setDispatchMode("AUTO"));
        var update = new AccountDTO();
        update.setId(1L);
        var dispatchConfig = scheduledRequest(LocalTime.of(19, 30));

        service.updateAccount(update, Set.of("id", "dispatchConfig"), dispatchConfig);

        verify(service.dispatchConfigService).update(
                eq(existing), eq(dispatchConfig), eq(true), any(LocalDateTime.class));
        verify(service.taskService, never()).forceHaltTask(any());
    }

    @Test
    void switchingWaitingScheduledRunToAutoCancelsItWithoutCreatingAnAutoTask() {
        var service = dispatchUpdateService();
        var existing = activeAccount(1L);
        var run = new AccountScheduledRunEntity().setId(41L).setAccountId(1L)
                .setStatus(AccountScheduledRunService.STATUS_WAITING);
        when(service.accountMapper.selectById(1L)).thenReturn(existing);
        when(service.taskAssignmentService.findByAccount(1L)).thenReturn(Optional.empty());
        when(service.scheduledRunService.findActiveByAccount(1L)).thenReturn(Optional.of(run));
        when(service.scheduledRunService.cancel(41L)).thenReturn(true);
        when(service.dispatchConfigService.getOrDefault(1L)).thenReturn(
                new AccountDispatchConfigEntity().setAccountId(1L)
                        .setDispatchMode(AccountDispatchConfigService.SCHEDULED)
                        .setScheduleTime(LocalTime.of(19, 30)));
        var update = new AccountDTO();
        update.setId(1L);
        var auto = new AccountDispatchConfigDTO();
        auto.setDispatchMode(AccountDispatchConfigService.AUTO);

        service.updateAccount(update, Set.of("id", "dispatchConfig"), auto);

        verify(service.dispatchConfigService).update(
                eq(existing), eq(auto), eq(false), any(LocalDateTime.class));
        verify(service.scheduledRunService).cancel(41L);
        verify(service.dispatchQueueService).remove(1L);
        verify(service.dispatchQueueService, never()).enqueueAuto(any());
    }

    @Test
    void activeWeekOnlyUpdateReusesExistingScheduledTimeInsteadOfResettingMode() {
        var service = dispatchUpdateService();
        var existing = activeAccount(1L);
        when(service.accountMapper.selectById(1L)).thenReturn(existing);
        when(service.taskAssignmentService.findByAccount(1L)).thenReturn(Optional.empty());
        when(service.scheduledRunService.findActiveByAccount(1L)).thenReturn(Optional.empty());
        when(service.dispatchConfigService.getOrDefault(1L)).thenReturn(
                new AccountDispatchConfigEntity().setAccountId(1L)
                        .setDispatchMode(AccountDispatchConfigService.SCHEDULED)
                        .setScheduleTime(LocalTime.of(19, 30)));
        var update = new AccountDTO();
        update.setId(1L);
        var active = new ActivationDate();
        active.getTuesday().setEnable(true);
        update.setActive(active);

        service.updateAccount(update, Set.of("id", "active"));

        var config = ArgumentCaptor.forClass(AccountDispatchConfigDTO.class);
        verify(service.dispatchConfigService).update(
                eq(existing), config.capture(), eq(false), any(LocalDateTime.class));
        assertEquals(AccountDispatchConfigService.SCHEDULED,
                config.getValue().getDispatchMode());
        assertEquals(LocalTime.of(19, 30), config.getValue().getScheduleTime());
    }

    @Test
    void freezingAccountCancelsWaitingScheduledRunAndRemovesItFromQueue() {
        var service = dispatchUpdateService();
        var existing = activeAccount(1L).setFreeze(0);
        var run = new AccountScheduledRunEntity().setId(41L).setAccountId(1L)
                .setStatus(AccountScheduledRunService.STATUS_WAITING);
        when(service.accountMapper.selectById(1L)).thenReturn(existing);
        when(service.scheduledRunService.findActiveByAccount(1L)).thenReturn(Optional.of(run));
        when(service.scheduledRunService.cancel(41L)).thenReturn(true);
        var update = new AccountDTO();
        update.setId(1L);
        update.setFreeze(1);

        service.updateAccount(update, Set.of("id", "freeze"));

        verify(service.scheduledRunService).cancel(41L);
        verify(service.dispatchConfigMapper).clearNext(1L);
        verify(service.dispatchQueueService).remove(1L);
    }

    @Test
    void unfreezingScheduledAccountWithoutRunCalculatesANewFutureOccurrence() {
        var service = dispatchUpdateService();
        var existing = activeAccount(1L).setFreeze(1);
        when(service.accountMapper.selectById(1L)).thenReturn(existing);
        when(service.scheduledRunService.findActiveByAccount(1L)).thenReturn(Optional.empty());
        when(service.taskAssignmentService.findByAccount(1L)).thenReturn(Optional.empty());
        when(service.dispatchConfigService.getOrDefault(1L)).thenReturn(
                new AccountDispatchConfigEntity().setAccountId(1L)
                        .setDispatchMode(AccountDispatchConfigService.SCHEDULED)
                        .setScheduleTime(LocalTime.of(19, 30)));
        var update = new AccountDTO();
        update.setId(1L);
        update.setFreeze(0);

        service.updateAccount(update, Set.of("id", "freeze"));

        var config = ArgumentCaptor.forClass(AccountDispatchConfigDTO.class);
        verify(service.dispatchConfigService).update(
                eq(existing), config.capture(), eq(false), any(LocalDateTime.class));
        assertEquals(AccountDispatchConfigService.SCHEDULED,
                config.getValue().getDispatchMode());
        assertEquals(LocalTime.of(19, 30), config.getValue().getScheduleTime());
    }

    @Test
    void accountListHydratesScheduledModeNextRunAndDisplayStatus() {
        var service = new AccountServiceImpl();
        service.accountMapper = mock(AccountMapper.class);
        service.dynamicInfo = new DynamicInfo();
        service.dailyLoginService = dailyLoginService(mock(LogMapper.class));
        service.dispatchConfigMapper = mock(AccountDispatchConfigMapper.class);
        service.scheduledRunMapper = mock(AccountScheduledRunMapper.class);
        var page = new Page<AccountEntity>(1, 10);
        page.setRecords(List.of(activeAccount(1L), activeAccount(2L)));
        page.setTotal(2);
        var next = LocalDateTime.of(2026, 7, 29, 19, 30);
        when(service.dispatchConfigMapper.selectBatchIds(any())).thenReturn(List.of(
                new AccountDispatchConfigEntity().setAccountId(1L)
                        .setDispatchMode(AccountDispatchConfigService.SCHEDULED)
                        .setScheduleTime(LocalTime.of(19, 30)).setNextScheduledAt(next),
                new AccountDispatchConfigEntity().setAccountId(2L)
                        .setDispatchMode(AccountDispatchConfigService.SCHEDULED)
                        .setScheduleTime(LocalTime.of(20, 0))));
        when(service.scheduledRunMapper.selectLatestByAccountIds(any())).thenReturn(List.of(
                new AccountScheduledRunEntity().setId(41L).setAccountId(1L)
                        .setStatus(AccountScheduledRunService.STATUS_SUCCEEDED),
                new AccountScheduledRunEntity().setId(42L).setAccountId(2L)
                        .setStatus(AccountScheduledRunService.STATUS_WAITING)));

        var result = service.getAccountWithSanVOPageQueryVO(page);

        assertEquals(AccountDispatchConfigService.SCHEDULED,
                result.getRecords().get(0).getDispatchMode());
        assertEquals(LocalTime.of(19, 30), result.getRecords().get(0).getScheduleTime());
        assertEquals(next, result.getRecords().get(0).getNextScheduledAt());
        assertEquals("NORMAL", result.getRecords().get(0).getScheduleStatus());
        assertEquals(AccountScheduledRunService.STATUS_WAITING,
                result.getRecords().get(1).getScheduleStatus());
        verify(service.scheduledRunMapper).selectLatestByAccountIds(
                org.mockito.ArgumentMatchers.argThat(ids -> ids.containsAll(Set.of(1L, 2L))));
    }

    private static AccountServiceImpl dispatchUpdateService() {
        var service = new AccountServiceImpl();
        service.accountMapper = mock(AccountMapper.class);
        service.dynamicInfo = new DynamicInfo();
        service.taskService = mock(TaskServiceImpl.class);
        service.dispatchQueueService = mock(DispatchQueueService.class);
        service.dispatchConfigService = mock(AccountDispatchConfigService.class);
        service.taskAssignmentService = mock(TaskAssignmentService.class);
        service.scheduledRunService = mock(AccountScheduledRunService.class);
        service.dispatchConfigMapper = mock(AccountDispatchConfigMapper.class);
        return service;
    }

    private static void initializeDispatchHydration(AccountServiceImpl service) {
        service.dispatchConfigMapper = mock(AccountDispatchConfigMapper.class);
        service.scheduledRunMapper = mock(AccountScheduledRunMapper.class);
    }

    private static AccountDispatchConfigDTO scheduledRequest(LocalTime time) {
        var request = new AccountDispatchConfigDTO();
        request.setDispatchMode(AccountDispatchConfigService.SCHEDULED);
        request.setScheduleTime(time);
        return request;
    }

    private static AccountEntity activeAccount(Long id) {
        return new AccountEntity().setId(id).setDelete(0).setFreeze(0)
                .setTaskType("daily").setExpireTime(LocalDateTime.of(2099, 1, 1, 0, 0));
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
