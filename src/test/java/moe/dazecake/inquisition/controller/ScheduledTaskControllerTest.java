package moe.dazecake.inquisition.controller;

import moe.dazecake.inquisition.model.vo.task.ScheduledTaskOverviewVO;
import moe.dazecake.inquisition.model.vo.task.ScheduledTaskStatusVO;
import moe.dazecake.inquisition.service.impl.ScheduledTaskMonitorService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ScheduledTaskControllerTest {

    @Test
    void returnsTheDynamicScheduledTaskOverview() throws Exception {
        var service = mock(ScheduledTaskMonitorService.class);
        var now = LocalDateTime.of(2026, 7, 27, 15, 0);
        var task = new ScheduledTaskStatusVO(
                "queue-maintenance", "队列巡检", "恢复冷却并去重", "0 */1 * * * *",
                "Asia/Shanghai", "每1分钟", "HEALTHY", true, "SUCCESS", "CRON",
                now.minusMinutes(1), now.minusMinutes(1), now.minusMinutes(1), null,
                now.plusMinutes(1), 25L, 0, 10L, null, now.minusMinutes(1));
        when(service.getOverview(null)).thenReturn(
                new ScheduledTaskOverviewVO(now, 1, 1, 0, 0, 0, 0, List.of(task)));
        var controller = new ScheduledTaskController();
        ReflectionTestUtils.setField(controller, "monitorService", service);
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(get("/showScheduledTaskList"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.tasks[0].key").value("queue-maintenance"))
                .andExpect(jsonPath("$.data.tasks[0].status").value("HEALTHY"))
                .andExpect(jsonPath("$.data.tasks[0].nextRunAt").exists());
    }
}
