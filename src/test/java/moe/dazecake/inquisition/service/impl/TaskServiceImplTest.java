package moe.dazecake.inquisition.service.impl;

import moe.dazecake.inquisition.mapper.AccountMapper;
import moe.dazecake.inquisition.model.entity.AccountEntity;
import moe.dazecake.inquisition.model.vo.account.AccountCooldownVO;
import moe.dazecake.inquisition.utils.DynamicInfo;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskServiceImplTest {

    @Test
    void lineBusyCooldownWritesReasonLogAndAdminNotice() {
        var service = new TaskServiceImpl();
        service.dynamicInfo = new DynamicInfo();
        service.logService = mock(LogServiceImpl.class);
        service.messageService = mock(MessageServiceImpl.class);
        service.accountMapper = mock(AccountMapper.class);

        var account = new AccountEntity()
                .setId(423L)
                .setName("账号172B1")
                .setAccount("18307339567")
                .setServer(1L)
                .setTaskType("daily");

        service.errorHandle(account, "device-1", "lineBusy");

        assertTrue(service.dynamicInfo.getFreezeUserInfoMap().containsKey(423L));
        assertEquals("lineBusy", service.dynamicInfo.getCooldownReasonMap().get(423L));
        assertTrue(service.dynamicInfo.getWaitUserList().contains(423L));
        verify(service.logService).logWarn(contains("账号临时冷却"), contains("账号172B1"));
        verify(service.messageService).pushAdmin(contains("账号临时冷却"), contains("lineBusy"));
    }

    @Test
    void activeCooldownTaskMapContainsAccountAndReason() {
        var service = new TaskServiceImpl();
        service.dynamicInfo = new DynamicInfo();
        service.accountMapper = mock(AccountMapper.class);

        var until = LocalDateTime.now().plusMinutes(30);
        service.dynamicInfo.getFreezeUserInfoMap().put(423L, until);
        service.dynamicInfo.getCooldownReasonMap().put(423L, "lineBusy");

        when(service.accountMapper.selectById(423L)).thenReturn(new AccountEntity()
                .setId(423L)
                .setName("账号172B1")
                .setAccount("18307339567")
                .setFreeze(0)
                .setDelete(0)
                .setExpireTime(LocalDateTime.now().plusDays(1)));

        AccountCooldownVO vo = service.getActiveCooldownTaskInfoMap().get(423L);

        assertNotNull(vo);
        assertEquals("账号172B1", vo.getName());
        assertEquals("18307339567", vo.getAccount());
        assertEquals(until, vo.getUntil());
        assertEquals("lineBusy", vo.getReason());
    }
}
