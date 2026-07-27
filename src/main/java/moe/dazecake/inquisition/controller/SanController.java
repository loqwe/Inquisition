package moe.dazecake.inquisition.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import moe.dazecake.inquisition.utils.DynamicInfo;
import moe.dazecake.inquisition.utils.Result;
import moe.dazecake.inquisition.service.impl.TaskAssignmentService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@Tag(name = "理智接口")
@ResponseBody
@RestController
public class SanController {
    @Resource
    private DynamicInfo dynamicInfo;

    @Resource
    private TaskAssignmentService taskAssignmentService;

    @Operation(summary = "理智上报")
    @PostMapping("/sanReport")
    public Result<String> SanReport(Integer san, Integer maxSan, String deviceToken, String assignmentId) {
        Result<String> result = new Result<>();
        var assignment = taskAssignmentService.findByDevice(deviceToken).orElse(null);
        if (assignmentId != null && !assignmentId.isBlank()
                && !taskAssignmentService.matchesSubmission(assignment, deviceToken, assignmentId)) {
            return Result.failed(409, "任务分配已失效");
        }
        var id = assignment == null ? dynamicInfo.getUserIdByDeviceToken(deviceToken) : assignment.getAccountId();
        if (id == null) {
            return Result.notFound("当前设备没有任务");
        }
        dynamicInfo.setUserSan(id, san, maxSan);
        return result.setCode(200).setMsg("success");
    }
}
