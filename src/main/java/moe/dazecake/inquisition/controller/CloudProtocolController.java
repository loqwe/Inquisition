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
    public Result<Map<String, Object>> heartBeat(@RequestBody CloudHeartbeatDTO req,
                                                 @RequestHeader(value = "X-Device-Token", required = false) String token) {
        if (req != null && (req.getDeviceToken() == null || req.getDeviceToken().isBlank())) req.setDeviceToken(token);
        return service.heartbeat(req);
    }

    @GetMapping("/getTask")
    public Result<CloudProtocolService.CloudTask> getTask(
            @RequestParam(required = false) String deviceToken,
            @RequestHeader(value = "X-Device-Token", required = false) String headerToken) {
        return service.getTask(deviceToken != null ? deviceToken : headerToken);
    }

    @PostMapping("/completeTask")
    public Result<String> complete(@RequestBody CloudTaskReportDTO req,
                                   @RequestHeader(value = "X-Device-Token", required = false) String token) { withToken(req, token); return service.complete(req); }

    @PostMapping("/failTask")
    public Result<String> fail(@RequestBody CloudTaskReportDTO req,
                               @RequestHeader(value = "X-Device-Token", required = false) String token) { withToken(req, token); return service.fail(req); }

    @PostMapping("/addLog")
    public Result<String> addLog(@RequestBody CloudLogDTO req,
                                 @RequestHeader(value = "X-Device-Token", required = false) String token) { withToken(req, token); return service.addLog(req); }

    private void withToken(Object req, String token) {
        if (token == null || req == null) return;
        if (req instanceof CloudTaskReportDTO) {
            CloudTaskReportDTO dto = (CloudTaskReportDTO) req;
            if (dto.getDeviceToken() == null || dto.getDeviceToken().isBlank()) dto.setDeviceToken(token);
        }
        if (req instanceof CloudLogDTO) {
            CloudLogDTO dto = (CloudLogDTO) req;
            if (dto.getDeviceToken() == null || dto.getDeviceToken().isBlank()) dto.setDeviceToken(token);
        }
    }
}
