package moe.dazecake.inquisition.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import moe.dazecake.inquisition.mapper.AccountMapper;
import moe.dazecake.inquisition.mapper.SklandCredentialMapper;
import moe.dazecake.inquisition.model.dto.skland.SklandStatusSyncDTO;
import moe.dazecake.inquisition.model.entity.AccountEntity;
import moe.dazecake.inquisition.model.entity.SklandCredentialEntity;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SklandStatusSyncServiceTest {

    @Test
    void explicitAccountMappingPersistsUidAndRuntimeSnapshot() {
        var service = new SklandStatusSyncService();
        service.accountMapper = mock(AccountMapper.class);
        service.credentialMapper = mock(SklandCredentialMapper.class);
        service.credentialService = mock(SklandCredentialService.class);
        service.accountRuntimeService = mock(AccountRuntimeService.class);
        when(service.accountMapper.selectById(398L)).thenReturn(new AccountEntity().setId(398L).setDelete(0));
        when(service.credentialMapper.selectById(398L)).thenReturn(null);
        var dto = snapshot().setAccountId(398L);

        var result = service.sync(dto);

        assertEquals(200, result.getCode());
        verify(service.credentialService).save(any(SklandCredentialEntity.class));
        verify(service.accountRuntimeService).recordSklandSnapshot(eq(398L), eq(20), eq(135),
                eq(1784490000L), any(LocalDateTime.class), any(LocalDateTime.class));
    }

    @Test
    void existingUidMappingCanResolveTheAccount() {
        var service = new SklandStatusSyncService();
        service.accountMapper = mock(AccountMapper.class);
        service.credentialMapper = mock(SklandCredentialMapper.class);
        service.credentialService = mock(SklandCredentialService.class);
        service.accountRuntimeService = mock(AccountRuntimeService.class);
        when(service.credentialMapper.selectOne(any(Wrapper.class))).thenReturn(
                new SklandCredentialEntity().setAccountId(398L).setUid("uid-1"));
        when(service.accountMapper.selectById(398L)).thenReturn(new AccountEntity().setId(398L).setDelete(0));

        var result = service.sync(snapshot());

        assertEquals(200, result.getCode());
        verify(service.accountRuntimeService).recordSklandSnapshot(eq(398L), eq(20), eq(135),
                eq(1784490000L), any(LocalDateTime.class), any(LocalDateTime.class));
    }

    private SklandStatusSyncDTO snapshot() {
        return new SklandStatusSyncDTO()
                .setUid("uid-1")
                .setChannelMasterId("1")
                .setCurrentSanity(20)
                .setMaxSanity(135)
                .setCompleteRecoveryTime(1784490000L)
                .setLastOnlineTs(1784488000L)
                .setObservedAt(1784489000L);
    }
}
