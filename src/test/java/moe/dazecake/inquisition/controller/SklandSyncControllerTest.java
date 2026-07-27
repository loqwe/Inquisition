package moe.dazecake.inquisition.controller;

import moe.dazecake.inquisition.model.dto.skland.SklandStatusSyncDTO;
import moe.dazecake.inquisition.service.impl.SklandStatusSyncService;
import moe.dazecake.inquisition.utils.Result;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SklandSyncControllerTest {

    @Test
    void rejectsAnInvalidCallbackToken() throws Exception {
        var controller = new SklandController();
        var syncService = mock(SklandStatusSyncService.class);
        ReflectionTestUtils.setField(controller, "statusSyncService", syncService);
        ReflectionTestUtils.setField(controller, "syncSecret", "sync-secret");
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/syncSklandStatus")
                        .header("X-Inquisition-Skland-Token", "wrong")
                        .contentType("application/json")
                        .content("{\"accountId\":398,\"uid\":\"uid-1\",\"currentSanity\":20,\"maxSanity\":135}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));

        verify(syncService, never()).sync(any());
    }

    @Test
    void acceptsAValidSignedCallback() throws Exception {
        var controller = new SklandController();
        var syncService = mock(SklandStatusSyncService.class);
        when(syncService.sync(any(SklandStatusSyncDTO.class))).thenReturn(Result.success("同步成功"));
        ReflectionTestUtils.setField(controller, "statusSyncService", syncService);
        ReflectionTestUtils.setField(controller, "syncSecret", "sync-secret");
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/syncSklandStatus")
                        .header("X-Inquisition-Skland-Token", "sync-secret")
                        .contentType("application/json")
                        .content("{\"accountId\":398,\"uid\":\"uid-1\",\"channelMasterId\":\"1\","
                                + "\"currentSanity\":20,\"maxSanity\":135,\"completeRecoveryTime\":1784490000,"
                                + "\"lastOnlineTs\":1784488000,\"observedAt\":1784489000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(syncService).sync(any(SklandStatusSyncDTO.class));
    }
}
