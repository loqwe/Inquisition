package moe.dazecake.inquisition.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import moe.dazecake.inquisition.mapper.AccountMapper;
import moe.dazecake.inquisition.model.entity.AccountEntity;
import moe.dazecake.inquisition.utils.DynamicInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

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
    void queryAccountSearchesExactNameOrAccountBeforeFuzzyMatch() {
        var service = spy(new AccountServiceImpl());
        service.dynamicInfo = new DynamicInfo();

        var exactPage = new Page<AccountEntity>(1, 10);
        exactPage.setRecords(List.of(new AccountEntity().setId(20L).setName("账号20").setAccount("13505732117")));
        exactPage.setTotal(1);

        doReturn(exactPage).when(service).queryExactAccountPage(1L, 10L, "账号20");

        service.queryAccount(1L, 10L, "账号20");

        verify(service).queryExactAccountPage(1L, 10L, "账号20");
        verify(service, never()).queryFuzzyAccountPage(any(), any(), any());
    }
}
