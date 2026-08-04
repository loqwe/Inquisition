package moe.dazecake.inquisition.service.impl;

import moe.dazecake.inquisition.mapper.AccountMapper;
import moe.dazecake.inquisition.mapper.LogMapper;
import moe.dazecake.inquisition.model.dto.log.AddLogDTO;
import moe.dazecake.inquisition.model.entity.AccountEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LogServiceImplTest {

    @Test
    void specialScanDoesNotPushWhenActiveAccountIsDuplicated() {
        var service = new LogServiceImpl();
        service.accountMapper = mock(AccountMapper.class);
        service.messageService = mock(MessageServiceImpl.class);
        service.logMapper = mock(LogMapper.class);
        service.accountRuntimeService = mock(AccountRuntimeService.class);

        var dto = new AddLogDTO();
        dto.setAccount("18307339567");
        dto.setDetail("高级资深干员");

        when(service.accountMapper.selectList(any())).thenReturn(List.of(
                new AccountEntity().setId(1L).setAccount("18307339567").setDelete(0),
                new AccountEntity().setId(2L).setAccount("18307339567").setDelete(0)
        ));

        service.specialScan(dto);

        verify(service.messageService, never()).push(any(), any(), any());
        verify(service.messageService).pushAdmin(any(), any());
    }

    @Test
    void specialScanPushesOnlyWhenOneActiveAccountMatches() {
        var service = new LogServiceImpl();
        service.accountMapper = mock(AccountMapper.class);
        service.messageService = mock(MessageServiceImpl.class);
        service.logMapper = mock(LogMapper.class);
        service.accountRuntimeService = mock(AccountRuntimeService.class);

        var dto = new AddLogDTO();
        dto.setAccount("18307339567");
        dto.setDetail("高级资深干员");

        var account = new AccountEntity().setId(1L).setAccount("18307339567").setDelete(0);
        when(service.accountMapper.selectList(any())).thenReturn(List.of(account));

        service.specialScan(dto);

        verify(service.messageService).push(account, "高级资深干员提示", "恭喜你获得了高级资深干员！快上游戏看看吧！");
        verify(service.messageService, never()).pushAdmin(any(), any());
    }
}
