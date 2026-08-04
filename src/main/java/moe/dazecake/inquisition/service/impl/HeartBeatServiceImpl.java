package moe.dazecake.inquisition.service.impl;

import moe.dazecake.inquisition.model.dto.heartbeat.HeartBeatDTO;
import moe.dazecake.inquisition.service.intf.HeartBeatService;
import moe.dazecake.inquisition.utils.DynamicInfo;
import moe.dazecake.inquisition.utils.GameDayClock;
import moe.dazecake.inquisition.utils.Result;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class HeartBeatServiceImpl implements HeartBeatService {

    @Resource
    DynamicInfo dynamicInfo;

    @Resource
    DeviceRuntimeService deviceRuntimeService;

    @Resource
    TaskAssignmentService taskAssignmentService;

    @Override
    public Result<String> postHeartBeat(HeartBeatDTO heartBeat) {
        Result<String> result = new Result<>();
        var now = GameDayClock.now();
        var haltRequested = deviceRuntimeService.recordHeartbeat(
                heartBeat.getDeviceToken(), heartBeat.getStatus(), heartBeat.getAssignmentId(),
                heartBeat.getClientVersion(), now);

        var assignment = taskAssignmentService.findByDevice(heartBeat.getDeviceToken()).orElse(null);
        if (heartBeat.getAssignmentId() != null && !heartBeat.getAssignmentId().isBlank()
                && !taskAssignmentService.matchesSubmission(
                assignment, heartBeat.getDeviceToken(), heartBeat.getAssignmentId())) {
            haltRequested = true;
            synchronized (dynamicInfo.getHaltList()) {
                if (!dynamicInfo.getHaltList().contains(heartBeat.getDeviceToken())) {
                    dynamicInfo.getHaltList().add(heartBeat.getDeviceToken());
                }
            }
        }


        if (dynamicInfo.getWaitUserList().isEmpty()) {
            result.setCode(200);
        } else {
            result.setCode(201);
        }

        //停机检查
        synchronized (dynamicInfo.getHaltList()) {
            if (haltRequested || dynamicInfo.getHaltList().contains(heartBeat.getDeviceToken())) {
                result.setCode(500);
            }
        }
        return result.setMsg("success");
    }

    @Override
    public Result<String> postHaltComplete(HeartBeatDTO heartBeat) {
        Result<String> result = new Result<>();

        //移除所有停机列表
        synchronized (dynamicInfo.getHaltList()) {
            while (dynamicInfo.getHaltList().contains(heartBeat.getDeviceToken())) {
                dynamicInfo.getHaltList().remove(heartBeat.getDeviceToken());
            }
        }

        return result.setCode(200).setMsg("success");
    }
}
