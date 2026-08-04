package moe.dazecake.inquisition.service.impl;

import moe.dazecake.inquisition.mapper.SklandCredentialMapper;
import moe.dazecake.inquisition.model.entity.SklandCredentialEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SklandCredentialServiceTest {

    @Test
    void accessTokenRefreshSelectsTheDefaultArknightsBinding() throws Exception {
        var service = new SklandCredentialService();
        service.credentialMapper = mock(SklandCredentialMapper.class);
        service.sklandClient = mock(SklandClient.class);
        var credential = new SklandCredentialEntity().setAccountId(398L)
                .setAccessToken("access-token");
        when(service.credentialMapper.selectById(398L)).thenReturn(credential);
        when(service.sklandClient.generateCredential("access-token"))
                .thenReturn(new SklandClient.GeneratedCredential("cred", "cred-token", "user-1"));
        when(service.sklandClient.getBindings(credential)).thenReturn(List.of(
                new SklandClient.Binding("other", "b", "其他游戏", true),
                new SklandClient.Binding("ark", "ark-channel", "明日方舟", true)));

        var result = service.ensureCredential(398L);

        assertTrue(result.isPresent());
        assertEquals("ark", result.get().getUid());
        assertEquals("ark-channel", result.get().getChannelMasterId());
        verify(service.credentialMapper).updateById(any(SklandCredentialEntity.class));
    }
}
