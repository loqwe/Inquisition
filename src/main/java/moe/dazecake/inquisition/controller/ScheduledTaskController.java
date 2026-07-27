package moe.dazecake.inquisition.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import moe.dazecake.inquisition.annotation.Login;
import moe.dazecake.inquisition.model.vo.task.ScheduledTaskOverviewVO;
import moe.dazecake.inquisition.service.impl.ScheduledTaskMonitorService;
import moe.dazecake.inquisition.utils.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@Tag(name = "脚本任务监控接口")
@ResponseBody
@RestController
public class ScheduledTaskController {

    @Resource
    ScheduledTaskMonitorService monitorService;

    @Login
    @Operation(summary = "查询脚本任务运行状态")
    @GetMapping("/showScheduledTaskList")
    public Result<ScheduledTaskOverviewVO> showScheduledTaskList() {
        return Result.success(monitorService.getOverview(null), "查询成功");
    }
}
