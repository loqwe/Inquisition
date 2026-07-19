package moe.dazecake.inquisition.controller;

import moe.dazecake.inquisition.model.dto.heartbeat.HeartBeatDTO;
import moe.dazecake.inquisition.model.dto.log.AddLogDTO;
import moe.dazecake.inquisition.service.impl.HeartBeatServiceImpl;
import moe.dazecake.inquisition.service.impl.LogServiceImpl;
import moe.dazecake.inquisition.service.impl.TaskServiceImpl;
import moe.dazecake.inquisition.utils.Result;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LegacyClientHttpContractTest {

    @Test
    void legacyHeartbeatBindsMissingMetadataAsNull() throws Exception {
        var service = mock(HeartBeatServiceImpl.class);
        when(service.postHeartBeat(any())).thenReturn(Result.success("success"));
        var controller = new HeartBeatController();
        ReflectionTestUtils.setField(controller, "heartBeatService", service);
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/heartBeat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":1001,\"deviceToken\":\"device-1\"}"))
                .andExpect(status().isOk());

        var captor = ArgumentCaptor.forClass(HeartBeatDTO.class);
        verify(service).postHeartBeat(captor.capture());
        assertEquals(1001, captor.getValue().getStatus());
        assertEquals("device-1", captor.getValue().getDeviceToken());
        assertNull(captor.getValue().getAssignmentId());
        assertNull(captor.getValue().getClientVersion());
    }

    @Test
    void legacyTaskReportsBindMissingAssignmentIdAsNull() throws Exception {
        var service = mock(TaskServiceImpl.class);
        when(service.completeTask("device-1", null, "image")).thenReturn(Result.success("success"));
        when(service.failTask("device-1", null, "network", "image")).thenReturn(Result.success("success"));
        var controller = new TaskController();
        controller.taskService = service;
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/completeTask").param("deviceToken", "device-1").param("imageUrl", "image"))
                .andExpect(status().isOk());
        mvc.perform(post("/failTask").param("deviceToken", "device-1").param("type", "network").param("imageUrl", "image"))
                .andExpect(status().isOk());

        verify(service).completeTask("device-1", null, "image");
        verify(service).failTask("device-1", null, "network", "image");
    }

    @Test
    void legacyLogPayloadBindsMissingIdentityFieldsAsNull() throws Exception {
        var service = mock(LogServiceImpl.class);
        var controller = new LogController();
        controller.logService = service;
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/addLog")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"level\":\"INFO\",\"title\":\"登录成功\",\"detail\":\"登录成功\",\"from\":\"device-1\",\"account\":\"legacy\"}"))
                .andExpect(status().isOk());

        var captor = ArgumentCaptor.forClass(AddLogDTO.class);
        verify(service).addLog(captor.capture(), eq(false));
        assertEquals("INFO", captor.getValue().getLevel());
        assertEquals("登录成功", captor.getValue().getTitle());
        assertEquals("device-1", captor.getValue().getFrom());
        assertEquals("legacy", captor.getValue().getAccount());
        assertNull(captor.getValue().getAssignmentId());
        assertNull(captor.getValue().getAccountId());
    }
}
