package moe.dazecake.inquisition.controller;

import moe.dazecake.inquisition.model.dto.cloud.*;
import moe.dazecake.inquisition.service.impl.CloudProtocolService;
import moe.dazecake.inquisition.utils.Result;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.Map;

@RestController
@RequestMapping("/cloud")
public class CloudProtocolController {
    @Resource private CloudProtocolService service;

    @PostMapping("/register")
    public Result<Map<String, String>> register(@RequestBody CloudRegisterDTO req) { return service.register(req); }

    @PostMapping("/heartBeat")
    public Result<Map<String, Object>> heartBeat(@RequestBody CloudHeartbeatDTO req) { return service.heartbeat(req); }

    @GetMapping("/getTask")
    public Result<CloudProtocolService.CloudTask> getTask(@RequestParam String deviceToken) { return service.getTask(deviceToken); }

    @PostMapping("/completeTask")
    public Result<String> complete(@RequestBody CloudTaskReportDTO req) { return service.complete(req); }

    @PostMapping("/failTask")
    public Result<String> fail(@RequestBody CloudTaskReportDTO req) { return service.fail(req); }

    @PostMapping("/addLog")
    public Result<String> addLog(@RequestBody CloudLogDTO req) { return service.addLog(req); }
}
