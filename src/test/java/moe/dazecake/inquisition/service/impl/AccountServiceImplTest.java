package moe.dazecake.inquisition.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import moe.dazecake.inquisition.mapper.AccountMapper;
import moe.dazecake.inquisition.model.entity.AccountEntity;
import moe.dazecake.inquisition.utils.DynamicInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

        var searchPage = new Page<AccountEntity>(1, 10);
        searchPage.setRecords(List.of(
                new AccountEntity().setId(1L).setName("账号1").setAccount("16603003649"),
                new AccountEntity().setId(10L).setName("账号10").setAccount("16603003650")
        ));
        searchPage.setTotal(2);

        when(service.accountMapper.searchActiveExactFirst(any(Page.class), eq("账号1"), isNull())).thenReturn(searchPage);

        var result = service.queryAccount(1L, 10L, "账号1");

        assertEquals(2, result.getTotal());
        assertEquals("账号1", result.getRecords().get(0).getName());
        assertEquals("账号10", result.getRecords().get(1).getName());
        verify(service.accountMapper).searchActiveExactFirst(any(Page.class), eq("账号1"), isNull());
    }
}
