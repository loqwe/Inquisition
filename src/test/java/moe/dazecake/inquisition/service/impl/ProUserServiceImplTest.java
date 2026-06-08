package moe.dazecake.inquisition.service.impl;

import moe.dazecake.inquisition.mapper.AccountMapper;
import moe.dazecake.inquisition.mapper.ProUserMapper;
import moe.dazecake.inquisition.model.entity.AccountEntity;
import moe.dazecake.inquisition.model.entity.ProUserEntity;
import moe.dazecake.inquisition.utils.DynamicInfo;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProUserServiceImplTest {

    @Test
    void deleteAndRecycleUserHardDeletesSubUserAndClearsRuntimeState() {
        var service = new ProUserServiceImpl();
        var proUserMapper = mock(ProUserMapper.class);
        var accountMapper = mock(AccountMapper.class);
        var taskService = mock(TaskServiceImpl.class);
        var dynamicInfo = new DynamicInfo();

        ReflectionTestUtils.setField(service, "proUserMapper", proUserMapper);
        ReflectionTestUtils.setField(service, "accountMapper", accountMapper);
        ReflectionTestUtils.setField(service, "taskService", taskService);
        ReflectionTestUtils.setField(service, "dynamicInfo", dynamicInfo);

        var proUser = new ProUserEntity();
        proUser.setId(1L);
        proUser.setBalance(0.0);
        proUser.setDiscount(1.0);
        when(proUserMapper.selectById(1L)).thenReturn(proUser);
        when(accountMapper.selectById(423L)).thenReturn(new AccountEntity()
                .setId(423L)
                .setAgent(1L)
                .setExpireTime(LocalDateTime.now().minusDays(1)));
        dynamicInfo.getUserSanInfoMap().put(423L, new moe.dazecake.inquisition.model.local.UserSan(1, 135));

        service.deleteAndRecycleUser(1L, 423L);

        verify(taskService).forceHaltTask(423L);
        verify(accountMapper).hardDeleteById(423L);
        org.junit.jupiter.api.Assertions.assertFalse(dynamicInfo.getUserSanInfoMap().containsKey(423L));
    }
}
