package moe.dazecake.inquisition.service.impl;

import moe.dazecake.inquisition.mapper.AccountMapper;
import moe.dazecake.inquisition.mapper.AccountRuntimeMapper;
import moe.dazecake.inquisition.mapper.LogMapper;
import moe.dazecake.inquisition.model.entity.AccountEntity;
import moe.dazecake.inquisition.model.entity.AccountRuntimeEntity;
import moe.dazecake.inquisition.model.entity.LogEntity;
import moe.dazecake.inquisition.model.entity.TaskAssignmentEntity;
import moe.dazecake.inquisition.utils.DynamicInfo;
import moe.dazecake.inquisition.utils.GameDayClock;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountRuntimeServiceTest {

    @Test
    void legacyLogIdentityUsesTheCurrentAssignmentAsAuthority() {
        var service = new AccountRuntimeService();
        service.taskAssignmentService = mock(TaskAssignmentService.class);
        service.accountMapper = mock(AccountMapper.class);
        var log = new LogEntity().setFrom("device-1").setAccount("stale-account");
        when(service.taskAssignmentService.findByDevice("device-1")).thenReturn(Optional.of(
                new TaskAssignmentEntity().setAssignmentId("assignment-current")
                        .setAccountId(398L).setDeviceToken("device-1")));

        service.enrichLogIdentity(log);

        assertEquals(398L, log.getAccountId());
        assertEquals("assignment-current", log.getAssignmentId());
        verify(service.accountMapper, never()).selectOne(any());
    }

    @Test
    void onlyAValidDeviceGameLogRefreshesTheNineHourAnchor() {
        var service = new AccountRuntimeService();
        service.runtimeMapper = mock(AccountRuntimeMapper.class);
        service.taskAssignmentService = mock(TaskAssignmentService.class);
        service.accountMapper = mock(AccountMapper.class);
        service.logMapper = mock(LogMapper.class);
        service.dynamicInfo = new DynamicInfo();
        service.messageService = mock(MessageServiceImpl.class);
        var time = LocalDateTime.of(2026, 7, 19, 13, 0);
        var log = new LogEntity().setAccountId(398L).setFrom("device-1")
                .setLevel("INFO").setTitle("登录成功").setDetail("登录成功").setTime(time);
        when(service.runtimeMapper.selectById(398L)).thenReturn(null);
        when(service.taskAssignmentService.recordProgress("device-1", null,
                "INFO", "登录成功", "登录成功")).thenReturn(true);

        service.onLog(log, false);

        verify(service.taskAssignmentService).recordProgress("device-1", null,
                "INFO", "登录成功", "登录成功");
        verify(service.runtimeMapper).insert(any(AccountRuntimeEntity.class));
    }

    @Test
    void missingLogCheckUsesSklandAndNotifiesOnlyWhenNoOnlineEvidenceExists() {
        var service = new AccountRuntimeService();
        service.runtimeMapper = mock(AccountRuntimeMapper.class);
        service.accountMapper = mock(AccountMapper.class);
        service.logMapper = mock(LogMapper.class);
        service.taskAssignmentService = mock(TaskAssignmentService.class);
        service.sklandCalibrationService = mock(SklandCalibrationService.class);
        service.dynamicInfo = new DynamicInfo();
        service.messageService = mock(MessageServiceImpl.class);
        var now = LocalDateTime.of(2026, 7, 19, 13, 10);
        var account = new AccountEntity().setId(398L).setName("账号774")
                .setDelete(0).setFreeze(0).setExpireTime(now.plusDays(1));
        var runtime = new AccountRuntimeEntity().setAccountId(398L)
                .setLastValidLogAt(LocalDateTime.of(2026, 7, 19, 4, 0))
                .setGameDayKey(LocalDateTime.of(2026, 7, 19, 4, 0).toLocalDate())
                .setMissingLogNotified(0);
        when(service.accountMapper.selectList(any())).thenReturn(List.of(account));
        when(service.runtimeMapper.selectById(398L)).thenReturn(runtime);
        when(service.sklandCalibrationService.calibrate(account, now)).thenReturn(Optional.of(
                new SklandCalibrationResult(0, 135, LocalDateTime.of(2026, 7, 18, 23, 0), now)));

        var result = service.checkMissingLogs(now);

        assertEquals(1, result.getMissingAccounts().size());
        assertEquals(1, runtime.getMissingLogNotified());
        verify(service.messageService).push(account, "账号状态提醒",
                "今天 04:00 后暂未检测到有效游戏日志，森空岛也未记录新的登录；请检查设备和账号状态。");
        verify(service.messageService).pushAdmin(contains("账号异常"), contains("账号774"));
    }

    @Test
    void missingLogCheckPreservesFreshSklandFieldsAfterCalibration() {
        var service = new AccountRuntimeService();
        service.runtimeMapper = mock(AccountRuntimeMapper.class);
        service.accountMapper = mock(AccountMapper.class);
        service.logMapper = mock(LogMapper.class);
        service.taskAssignmentService = mock(TaskAssignmentService.class);
        service.sklandCalibrationService = mock(SklandCalibrationService.class);
        service.dynamicInfo = new DynamicInfo();
        service.messageService = mock(MessageServiceImpl.class);
        var now = LocalDateTime.of(2026, 7, 19, 13, 10);
        var account = new AccountEntity().setId(398L).setName("账号774")
                .setDelete(0).setFreeze(0).setExpireTime(now.plusDays(1));
        var initial = new AccountRuntimeEntity().setAccountId(398L)
                .setLastValidLogAt(LocalDateTime.of(2026, 7, 19, 4, 0))
                .setGameDayKey(LocalDateTime.of(2026, 7, 19, 4, 0).toLocalDate())
                .setMissingLogNotified(0);
        var refreshed = new AccountRuntimeEntity().setAccountId(398L)
                .setLastValidLogAt(initial.getLastValidLogAt())
                .setGameDayKey(initial.getGameDayKey())
                .setMissingLogNotified(0)
                .setSanity(18).setMaxSanity(135)
                .setLastSklandQueryAt(now).setSanityObservedAt(now);
        when(service.accountMapper.selectList(any())).thenReturn(List.of(account));
        when(service.runtimeMapper.selectById(398L)).thenReturn(initial, refreshed);
        when(service.sklandCalibrationService.calibrate(account, now)).thenReturn(Optional.of(
                new SklandCalibrationResult(18, 135, LocalDateTime.of(2026, 7, 19, 12, 30), now)));

        service.checkMissingLogs(now);

        verify(service.runtimeMapper).updateById(org.mockito.ArgumentMatchers.argThat(saved ->
                Integer.valueOf(18).equals(saved.getSanity())
                        && Integer.valueOf(135).equals(saved.getMaxSanity())
                        && now.equals(saved.getLastSklandQueryAt())));
    }

    @Test
    void unavailableSklandCalibrationDoesNotCreateAFalseAccountAlert() {
        var service = new AccountRuntimeService();
        service.runtimeMapper = mock(AccountRuntimeMapper.class);
        service.accountMapper = mock(AccountMapper.class);
        service.logMapper = mock(LogMapper.class);
        service.taskAssignmentService = mock(TaskAssignmentService.class);
        service.sklandCalibrationService = mock(SklandCalibrationService.class);
        service.dynamicInfo = new DynamicInfo();
        service.messageService = mock(MessageServiceImpl.class);
        var now = LocalDateTime.of(2026, 7, 19, 13, 10);
        var account = new AccountEntity().setId(398L).setName("账号398")
                .setDelete(0).setFreeze(0).setExpireTime(now.plusDays(1));
        var runtime = new AccountRuntimeEntity().setAccountId(398L)
                .setLastValidLogAt(LocalDateTime.of(2026, 7, 19, 4, 0))
                .setGameDayKey(LocalDateTime.of(2026, 7, 19, 4, 0).toLocalDate())
                .setMissingLogNotified(0);
        when(service.accountMapper.selectList(any())).thenReturn(List.of(account));
        when(service.runtimeMapper.selectById(398L)).thenReturn(runtime);
        when(service.sklandCalibrationService.calibrate(account, now)).thenReturn(Optional.empty());

        var result = service.checkMissingLogs(now);

        assertTrue(result.getMissingAccounts().isEmpty());
        assertEquals(0, runtime.getMissingLogNotified());
        verify(service.messageService, never()).push(any(), any(), any());
        verify(service.messageService, never()).pushAdmin(any(), any());
    }

    @Test
    void callbackSnapshotStoresSklandCurrentWithoutRecoveryProjection() {
        var service = new AccountRuntimeService();
        service.runtimeMapper = mock(AccountRuntimeMapper.class);
        service.dynamicInfo = new DynamicInfo();
        when(service.runtimeMapper.selectById(398L)).thenReturn(null);
        var observedAt = LocalDateTime.of(2026, 7, 20, 4, 5);
        var lastOnlineAt = observedAt.minusMinutes(10);
        var completeRecoveryTime = observedAt.atZone(GameDayClock.ZONE_ID).toEpochSecond()
                + (210L - 103L) * 360L;

        service.recordSklandSnapshot(398L, 0, 210, completeRecoveryTime, lastOnlineAt, observedAt);

        verify(service.runtimeMapper).insert(org.mockito.ArgumentMatchers.argThat(runtime ->
                Integer.valueOf(0).equals(runtime.getSanity())
                        && Integer.valueOf(210).equals(runtime.getMaxSanity())
                        && "SKLAND_CALLBACK".equals(runtime.getSanitySource())
                        && lastOnlineAt.equals(runtime.getLastOnlineAt())
                        && observedAt.equals(runtime.getLastSklandQueryAt())));
        assertEquals(0, service.dynamicInfo.getUserSanInfoMap().get(398L).getSan());
    }

    @Test
    void ocrSnapshotUpdatesRuntimeAndInMemorySanity() {
        var service = new AccountRuntimeService();
        service.runtimeMapper = mock(AccountRuntimeMapper.class);
        service.dynamicInfo = new DynamicInfo();
        when(service.runtimeMapper.selectById(398L)).thenReturn(null);
        var observedAt = LocalDateTime.of(2026, 7, 21, 16, 0);

        service.recordOcrSnapshot(398L, 1, 210, observedAt);

        verify(service.runtimeMapper).insert(org.mockito.ArgumentMatchers.argThat(runtime ->
                Integer.valueOf(1).equals(runtime.getSanity())
                        && Integer.valueOf(210).equals(runtime.getMaxSanity())
                        && "LOCAL_OCR".equals(runtime.getSanitySource())
                        && observedAt.equals(runtime.getSanityObservedAt())));
        assertEquals(1, service.dynamicInfo.getUserSanInfoMap().get(398L).getSan());
    }

    @Test
    void staleOcrSnapshotCannotOverwriteANewerSklandSnapshot() {
        var service = new AccountRuntimeService();
        service.runtimeMapper = mock(AccountRuntimeMapper.class);
        service.dynamicInfo = new DynamicInfo();
        var sklandAt = LocalDateTime.of(2026, 7, 21, 16, 5);
        when(service.runtimeMapper.selectById(398L)).thenReturn(new AccountRuntimeEntity()
                .setAccountId(398L)
                .setSanity(20)
                .setMaxSanity(210)
                .setSanitySource("SKLAND_CALLBACK")
                .setSanityObservedAt(sklandAt));

        service.recordOcrSnapshot(398L, 1, 210, sklandAt.minusMinutes(5));

        verify(service.runtimeMapper, never()).updateById(any());
        verify(service.runtimeMapper, never()).insert(any());
    }

    @Test
    void staleAssignmentLogCannotRefreshTheRuntimeAnchor() {
        var service = new AccountRuntimeService();
        service.runtimeMapper = mock(AccountRuntimeMapper.class);
        service.taskAssignmentService = mock(TaskAssignmentService.class);
        service.accountMapper = mock(AccountMapper.class);
        service.logMapper = mock(LogMapper.class);
        service.dynamicInfo = new DynamicInfo();
        service.messageService = mock(MessageServiceImpl.class);
        var log = new LogEntity().setAccountId(398L).setFrom("device-1")
                .setAssignmentId("assignment-old")
                .setLevel("INFO").setTitle("登录成功").setDetail("登录成功")
                .setTime(LocalDateTime.of(2026, 7, 19, 13, 0));
        when(service.taskAssignmentService.recordProgress("device-1", "assignment-old",
                "INFO", "登录成功", "登录成功")).thenReturn(false);

        assertTrue(!service.onLog(log, false));

        verify(service.runtimeMapper, never()).insert(any());
        verify(service.runtimeMapper, never()).updateById(any());
    }

    @Test
    void legacyLogWithoutCurrentAssignmentDoesNotRefreshRuntimeAnchor() {
        var service = new AccountRuntimeService();
        service.runtimeMapper = mock(AccountRuntimeMapper.class);
        service.taskAssignmentService = mock(TaskAssignmentService.class);
        service.accountMapper = mock(AccountMapper.class);
        service.logMapper = mock(LogMapper.class);
        service.dynamicInfo = new DynamicInfo();
        service.messageService = mock(MessageServiceImpl.class);
        var log = new LogEntity().setAccountId(398L).setFrom("device-1")
                .setLevel("INFO").setTitle("登录成功").setDetail("登录成功")
                .setTime(LocalDateTime.now());
        when(service.taskAssignmentService.recordProgress(
                "device-1", null, "INFO", "登录成功", "登录成功")).thenReturn(false);

        assertTrue(!service.onLog(log, false));
        verify(service.runtimeMapper, never()).insert(any());
        verify(service.runtimeMapper, never()).updateById(any());
    }
}
