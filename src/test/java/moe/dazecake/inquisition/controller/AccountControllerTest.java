package moe.dazecake.inquisition.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import moe.dazecake.inquisition.model.dto.account.AccountDTO;
import moe.dazecake.inquisition.model.dto.account.AccountDispatchConfigDTO;
import moe.dazecake.inquisition.service.impl.AccountDispatchConfigService;
import moe.dazecake.inquisition.service.impl.AccountServiceImpl;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AccountControllerTest {

    @Test
    void updateAccountSeparatesOptionalDispatchConfigFromLegacyDeviceDto() throws Exception {
        var service = mock(AccountServiceImpl.class);
        var controller = controller(service);
        var payload = controller.objectMapper.readTree("{\"id\":1,\"active\":{\"monday\":{\"enable\":true}},"
                + "\"dispatchConfig\":{\"dispatchMode\":\"SCHEDULED\",\"scheduleTime\":\"19:30\"}}");

        var result = controller.updateAccount(payload);

        assertEquals(200, result.getCode());
        var account = ArgumentCaptor.forClass(AccountDTO.class);
        @SuppressWarnings("unchecked")
        var fields = ArgumentCaptor.forClass(Set.class);
        var config = ArgumentCaptor.forClass(AccountDispatchConfigDTO.class);
        verify(service).updateAccount(account.capture(), fields.capture(), config.capture());
        assertTrue(account.getValue().getActive().getMonday().isEnable());
        assertTrue(fields.getValue().contains("dispatchConfig"));
        assertEquals(AccountDispatchConfigService.SCHEDULED, config.getValue().getDispatchMode());
        assertEquals(LocalTime.of(19, 30), config.getValue().getScheduleTime());
    }

    @Test
    void legacyUpdateWithoutDispatchConfigPreservesTheExistingSchedule() throws Exception {
        var service = mock(AccountServiceImpl.class);
        var controller = controller(service);

        var result = controller.updateAccount(
                controller.objectMapper.readTree("{\"id\":1,\"freeze\":0}"));

        assertEquals(200, result.getCode());
        var config = ArgumentCaptor.forClass(AccountDispatchConfigDTO.class);
        @SuppressWarnings("unchecked")
        var fields = ArgumentCaptor.forClass(Set.class);
        verify(service).updateAccount(any(AccountDTO.class), fields.capture(), config.capture());
        assertFalse(fields.getValue().contains("dispatchConfig"));
        assertNull(config.getValue());
    }

    @Test
    void updateAccountAcceptsMultipleScheduleTimes() throws Exception {
        var service = mock(AccountServiceImpl.class);
        var controller = controller(service);
        var payload = controller.objectMapper.readTree("{\"id\":1,"
                + "\"dispatchConfig\":{\"dispatchMode\":\"SCHEDULED\","
                + "\"scheduleTimes\":[\"08:00\",\"14:00\",\"19:30\"]}}");

        var result = controller.updateAccount(payload);

        assertEquals(200, result.getCode());
        var config = ArgumentCaptor.forClass(AccountDispatchConfigDTO.class);
        verify(service).updateAccount(any(AccountDTO.class), any(), config.capture());
        assertEquals(List.of(LocalTime.of(8, 0), LocalTime.of(14, 0),
                LocalTime.of(19, 30)), config.getValue().getScheduleTimes());
    }

    @Test
    void updateAccountAcceptsAutoModeWithoutScheduleFields() throws Exception {
        var service = mock(AccountServiceImpl.class);
        var controller = controller(service);
        var payload = controller.objectMapper.readTree("{\"id\":1,"
                + "\"dispatchConfig\":{\"dispatchMode\":\"AUTO\"}}");

        var result = controller.updateAccount(payload);

        assertEquals(200, result.getCode());
        var config = ArgumentCaptor.forClass(AccountDispatchConfigDTO.class);
        verify(service).updateAccount(any(AccountDTO.class), any(), config.capture());
        assertEquals(AccountDispatchConfigService.AUTO, config.getValue().getDispatchMode());
        assertNull(config.getValue().getScheduleTime());
        assertNull(config.getValue().getScheduleTimes());
    }

    @Test
    void invalidDispatchConfigReturnsAParameterErrorWithoutSaving() throws Exception {
        var service = mock(AccountServiceImpl.class);
        var controller = controller(service);
        var malformed = controller.objectMapper.readTree("{\"id\":1,"
                + "\"dispatchConfig\":{\"dispatchMode\":\"SCHEDULED\",\"scheduleTime\":\"bad\"}}");

        var result = controller.updateAccount(malformed);

        assertEquals(400, result.getCode());
        verify(service, never()).updateAccount(any(), any(), any());
    }

    @Test
    void serviceValidationErrorIsReturnedAsAParameterError() throws Exception {
        var service = mock(AccountServiceImpl.class);
        doThrow(new IllegalArgumentException("至少启用一个星期"))
                .when(service).updateAccount(any(), any(), any());
        var controller = controller(service);
        var payload = controller.objectMapper.readTree("{\"id\":1,"
                + "\"dispatchConfig\":{\"dispatchMode\":\"SCHEDULED\",\"scheduleTime\":\"19:30\"}}");

        var result = controller.updateAccount(payload);

        assertEquals(400, result.getCode());
        assertEquals("至少启用一个星期", result.getMsg());
    }

    @Test
    void unknownDispatchFieldIsRejectedBeforeSaving() throws Exception {
        var service = mock(AccountServiceImpl.class);
        var controller = controller(service);
        var payload = controller.objectMapper.readTree("{\"id\":1,"
                + "\"dispatchConfig\":{\"dispatchMode\":\"SCHEDULED\","
                + "\"scheduleTime\":\"19:30\",\"priority\":200}}");

        var result = controller.updateAccount(payload);

        assertEquals(400, result.getCode());
        verify(service, never()).updateAccount(any(), any(), any());
    }

    @Test
    void showAccountForwardsMissingLoginFilter() {
        var service = mock(AccountServiceImpl.class);
        var controller = controller(service);

        controller.showAccount(1L, 10L, null, null, null, null, "missing");

        verify(service).queryAllAccount(1L, 10L, null, null, null, null, "missing");
    }

    private static AccountController controller(AccountServiceImpl service) {
        var controller = new AccountController();
        controller.accountService = service;
        controller.objectMapper = new ObjectMapper().findAndRegisterModules();
        return controller;
    }
}
