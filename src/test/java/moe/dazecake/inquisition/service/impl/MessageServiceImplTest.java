package moe.dazecake.inquisition.service.impl;

import moe.dazecake.inquisition.model.entity.AccountEntity;
import moe.dazecake.inquisition.model.entity.AdminEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MessageServiceImplTest {

    @Test
    void userNotificationIsolatedFromWxPusherFailure() {
        var service = new MessageServiceImpl();
        service.enableWxPusher = true;
        service.enableMail = false;
        service.wxPusherService = mock(WXPusherServiceImpl.class);
        var account = new AccountEntity();
        account.getNotice().getWxUID().setEnable(true);
        account.getNotice().getWxUID().setText("UID-user");
        doThrow(new IllegalStateException("wx unavailable"))
                .when(service.wxPusherService)
                .push(anyInt(), anyString(), anyString(), isNull());

        assertDoesNotThrow(() -> service.push(account, "title", "content"));
    }

    @Test
    void adminNotificationContinuesToPushPlusAfterWxPusherFailure() {
        var service = new MessageServiceImpl();
        service.enableWxPusher = true;
        service.enableMail = false;
        service.wxPusherService = mock(WXPusherServiceImpl.class);
        service.pushPlusService = mock(PushPlusServiceImpl.class);
        var admin = new AdminEntity();
        admin.setNotice("{\"wxPusherEnable\":true,"
                + "\"wxPusherUid\":\"UID-admin\","
                + "\"pushPlusEnable\":true,"
                + "\"pushPlusToken\":\"PUSH-token\"}");
        doThrow(new IllegalStateException("wx unavailable"))
                .when(service.wxPusherService)
                .push(anyInt(), anyString(), anyString(), isNull());

        assertDoesNotThrow(() -> service.pushAdmin(List.of(admin), "title", "content"));

        verify(service.pushPlusService).push("PUSH-token", "title", "# title\n\ncontent");
    }
}
