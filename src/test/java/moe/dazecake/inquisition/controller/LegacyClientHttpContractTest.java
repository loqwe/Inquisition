package moe.dazecake.inquisition.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import moe.dazecake.inquisition.model.dto.account.AccountDTO;
import moe.dazecake.inquisition.model.dto.heartbeat.HeartBeatDTO;
import moe.dazecake.inquisition.model.dto.log.AddLogDTO;
import moe.dazecake.inquisition.model.entity.AccountEntity;
import moe.dazecake.inquisition.model.entity.ConfigEntitySet.ConfigEntity;
import moe.dazecake.inquisition.model.entity.ConfigEntitySet.Fight;
import moe.dazecake.inquisition.model.entity.TaskAssignmentEntity;
import moe.dazecake.inquisition.model.local.DispatchIntent;
import moe.dazecake.inquisition.service.impl.HeartBeatServiceImpl;
import moe.dazecake.inquisition.service.impl.LogServiceImpl;
import moe.dazecake.inquisition.service.impl.TaskAssignmentService;
import moe.dazecake.inquisition.service.impl.TaskServiceImpl;
import moe.dazecake.inquisition.utils.Result;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LegacyClientHttpContractTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

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

    @Test
    void legacyLogUsesQueryDeviceTokenWhenPayloadSourceIsMissing() throws Exception {
        var service = mock(LogServiceImpl.class);
        var controller = new LogController();
        controller.logService = service;
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/addLog")
                        .param("deviceToken", "device-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"level\":\"INFO\",\"title\":\"登录成功\",\"account\":\"legacy\"}"))
                .andExpect(status().isOk());

        var captor = ArgumentCaptor.forClass(AddLogDTO.class);
        verify(service).addLog(captor.capture(), eq(false));
        assertEquals("device-1", captor.getValue().getFrom());
    }

    @Test
    void normalDevicePayloadIsIdenticalAcrossAutoManualAndScheduledDispatchSources() {
        var auto = normalDevicePayload(DispatchIntent.SOURCE_AUTO, null, "assignment-auto");
        var manual = normalDevicePayload(DispatchIntent.SOURCE_MANUAL, null, "assignment-manual");
        var scheduled = normalDevicePayload(
                DispatchIntent.SOURCE_SCHEDULED, 41L, "assignment-scheduled");

        assertEquals(auto, manual);
        assertEquals(auto, scheduled);
    }

    private ObjectNode normalDevicePayload(String source, Long scheduledRunId, String assignmentId) {
        var config = new ConfigEntity();
        config.getDaily().setFight(new ArrayList<>(List.of(new Fight("1-7", 3))));
        config.getDaily().setMail(true);
        config.getDaily().setFriend(true);
        config.getDaily().setCredit(true);
        config.getDaily().setTask(true);
        var fixedAt = LocalDateTime.of(2026, 7, 28, 19, 30);
        var account = new AccountEntity().setId(7L).setName("账号7")
                .setAccount("16603003649").setPassword("fixture-password")
                .setTaskType("daily").setConfig(config)
                .setCreateTime(fixedAt.minusDays(1)).setUpdateTime(fixedAt)
                .setExpireTime(fixedAt.plusMonths(1));
        var assignment = new TaskAssignmentEntity().setAssignmentId(assignmentId)
                .setAccountId(7L).setTaskType("daily")
                .setTaskMode(TaskAssignmentService.MODE_NORMAL)
                .setDispatchSource(source).setScheduledRunId(scheduledRunId);

        AccountDTO payload = ReflectionTestUtils.invokeMethod(
                new TaskServiceImpl(), "buildTaskAccountDTO", account, assignment);
        var tree = (ObjectNode) OBJECT_MAPPER.valueToTree(payload);

        assertEquals("daily", tree.path("taskType").asText());
        assertTrue(tree.path("config").has("daily"));
        assertNoDispatchField(tree, "dispatchSource");
        assertNoDispatchField(tree, "scheduledRunId");
        assertNoDispatchField(tree, "dispatchMode");
        assertNoDispatchField(tree, "scheduleTime");
        assertNoDispatchField(tree, "nextScheduledAt");
        assertNoDispatchField(tree, "scheduleStatus");
        tree.remove("assignmentId");
        return tree;
    }

    private void assertNoDispatchField(JsonNode tree, String fieldName) {
        assertFalse(tree.findValue(fieldName) != null,
                () -> "legacy device payload exposed " + fieldName);
    }
}
