package moe.dazecake.inquisition.controller;

import moe.dazecake.inquisition.model.entity.TaskAssignmentEntity;
import moe.dazecake.inquisition.service.impl.TaskAssignmentService;
import moe.dazecake.inquisition.utils.DynamicInfo;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SanControllerLegacyContractTest {

    @Test
    void legacySanityReportUsesCurrentDeviceAssignment() throws Exception {
        var controller = new SanController();
        var dynamicInfo = new DynamicInfo();
        var assignments = mock(TaskAssignmentService.class);
        var assignment = new TaskAssignmentEntity()
                .setAssignmentId("assignment-current")
                .setAccountId(398L)
                .setDeviceToken("device-1");
        when(assignments.findByDevice("device-1")).thenReturn(Optional.of(assignment));
        ReflectionTestUtils.setField(controller, "dynamicInfo", dynamicInfo);
        ReflectionTestUtils.setField(controller, "taskAssignmentService", assignments);
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/sanReport")
                        .param("san", "18")
                        .param("maxSan", "135")
                        .param("deviceToken", "device-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertEquals(18, dynamicInfo.getUserSanInfoMap().get(398L).getSan());
        assertEquals(135, dynamicInfo.getUserSanInfoMap().get(398L).getMaxSan());
    }
}
