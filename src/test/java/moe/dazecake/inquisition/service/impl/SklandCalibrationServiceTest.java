package moe.dazecake.inquisition.service.impl;

import moe.dazecake.inquisition.mapper.AccountRuntimeMapper;
import moe.dazecake.inquisition.model.entity.AccountEntity;
import moe.dazecake.inquisition.model.entity.AccountRuntimeEntity;
import moe.dazecake.inquisition.model.entity.SklandCredentialEntity;
import moe.dazecake.inquisition.utils.DynamicInfo;
import moe.dazecake.inquisition.utils.GameDayClock;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SklandCalibrationServiceTest {

    @Test
    void calibrationStoresSklandCurrentWithoutRecoveryProjection() throws Exception {
        var service = new SklandCalibrationService();
        service.credentialService = mock(SklandCredentialService.class);
        service.runtimeMapper = mock(AccountRuntimeMapper.class);
        service.dynamicInfo = new DynamicInfo();
        service.sklandClient = mock(SklandClient.class);
        var account = new AccountEntity().setId(398L);
        var credential = new SklandCredentialEntity().setAccountId(398L)
                .setCred("cred").setCredToken("token").setUid("uid");
        var now = LocalDateTime.of(2026, 7, 19, 13, 0);
        when(service.runtimeMapper.selectById(398L)).thenReturn(null);
        when(service.credentialService.ensureCredential(398L)).thenReturn(java.util.Optional.of(credential));
        when(service.sklandClient.queryPlayerInfo(credential)).thenReturn(
                new SklandPlayerStatus(0, 210,
                        now.atZone(GameDayClock.ZONE_ID).toEpochSecond()
                                + (210L - 103L) * 360L,
                        LocalDateTime.of(2026, 7, 19, 12, 30)));

        var result = service.calibrate(account, now);

        assertTrue(result.isPresent());
        assertEquals(0, result.get().getSanity());
        assertEquals(0, service.dynamicInfo.getUserSanInfoMap().get(398L).getSan());
        verify(service.runtimeMapper).insert(any(AccountRuntimeEntity.class));
    }

    @Test
    void freshSuccessfulCalibrationIsReusedForOneHour() throws Exception {
        var service = new SklandCalibrationService();
        service.credentialService = mock(SklandCredentialService.class);
        service.runtimeMapper = mock(AccountRuntimeMapper.class);
        service.dynamicInfo = new DynamicInfo();
        service.sklandClient = mock(SklandClient.class);
        var now = LocalDateTime.of(2026, 7, 19, 13, 30);
        when(service.runtimeMapper.selectById(398L)).thenReturn(new AccountRuntimeEntity()
                .setAccountId(398L).setSanity(20).setMaxSanity(135)
                .setLastOnlineAt(now.minusMinutes(10)).setSanityObservedAt(now.minusMinutes(30))
                .setLastSklandQueryAt(now.minusMinutes(30)));

        var result = service.calibrate(new AccountEntity().setId(398L), now);

        assertTrue(result.isPresent());
        assertEquals(20, result.get().getSanity());
        verify(service.credentialService, never()).ensureCredential(any());
        verify(service.sklandClient, never()).queryPlayerInfo(any());
    }

    @Test
    void credentialErrorRefreshesTokenAndRetriesOnce() throws Exception {
        var service = new SklandCalibrationService();
        service.credentialService = mock(SklandCredentialService.class);
        service.runtimeMapper = mock(AccountRuntimeMapper.class);
        service.dynamicInfo = new DynamicInfo();
        service.sklandClient = mock(SklandClient.class);
        var account = new AccountEntity().setId(398L);
        var credential = new SklandCredentialEntity().setAccountId(398L)
                .setCred("cred").setCredToken("expired-token").setUid("uid");
        var now = LocalDateTime.of(2026, 7, 19, 14, 0);
        when(service.runtimeMapper.selectById(398L)).thenReturn(null);
        when(service.credentialService.ensureCredential(398L)).thenReturn(Optional.of(credential));
        when(service.sklandClient.queryPlayerInfo(credential))
                .thenThrow(new IOException("森空岛请求失败: code=10002"))
                .thenReturn(new SklandPlayerStatus(21, 135, 0, now.minusMinutes(5)));
        when(service.sklandClient.refreshCredToken("cred")).thenReturn("refreshed-token");

        var result = service.calibrate(account, now);

        assertTrue(result.isPresent());
        assertEquals("refreshed-token", credential.getCredToken());
        verify(service.credentialService).save(credential);
        verify(service.runtimeMapper).insert(any(AccountRuntimeEntity.class));
    }

    @Test
    void queryFailureIsRecordedWithoutMarkingCalibrationSuccessful() throws Exception {
        var service = new SklandCalibrationService();
        service.credentialService = mock(SklandCredentialService.class);
        service.runtimeMapper = mock(AccountRuntimeMapper.class);
        service.dynamicInfo = new DynamicInfo();
        service.sklandClient = mock(SklandClient.class);
        var credential = new SklandCredentialEntity().setAccountId(398L)
                .setCred("cred").setCredToken("token").setUid("uid");
        when(service.runtimeMapper.selectById(398L)).thenReturn(null);
        when(service.credentialService.ensureCredential(398L)).thenReturn(Optional.of(credential));
        when(service.sklandClient.queryPlayerInfo(credential)).thenThrow(new IOException("network timeout"));

        var result = service.calibrate(new AccountEntity().setId(398L),
                LocalDateTime.of(2026, 7, 19, 15, 0));

        assertFalse(result.isPresent());
        verify(service.runtimeMapper).insert(org.mockito.ArgumentMatchers.argThat(runtime ->
                runtime.getLastSklandQueryAt() != null
                        && "network timeout".equals(runtime.getLastError())
                        && runtime.getSanitySource() == null));
    }
}
