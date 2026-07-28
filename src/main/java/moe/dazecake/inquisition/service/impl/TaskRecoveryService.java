package moe.dazecake.inquisition.service.impl;

import lombok.extern.slf4j.Slf4j;
import moe.dazecake.inquisition.mapper.AccountMapper;
import moe.dazecake.inquisition.model.entity.AccountEntity;
import moe.dazecake.inquisition.model.entity.TaskAssignmentEntity;
import moe.dazecake.inquisition.utils.DynamicInfo;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Service
public class TaskRecoveryService {
    private static final long STARTED_TASK_COOLDOWN_MINUTES = 10;

    @Resource
    TaskAssignmentService taskAssignmentService;

    @Resource
    AccountMapper accountMapper;

    @Resource
    DynamicInfo dynamicInfo;

    @Resource
    MessageServiceImpl messageService;

    @Resource
    SklandCalibrationService sklandCalibrationService;

    @Resource
    DispatchQueueService dispatchQueueService;

    public void recoverDeviceOffline(String deviceToken, LocalDateTime now) {
        var assignment = taskAssignmentService.findByDevice(deviceToken).orElse(null);
        if (assignment == null) {
            return;
        }
        if (now != null && assignment.getAssignedAt() != null
                && assignment.getAssignedAt().isAfter(now)) {
            return;
        }
        addHaltRequest(deviceToken);
        var account = accountMapper.selectById(assignment.getAccountId());
        if (account == null || isDeleted(account)) {
            taskAssignmentService.closeAssignment(assignment, "INVALID", "account no longer exists", false);
            return;
        }

        if (!isGameStarted(assignment)) {
            if (taskAssignmentService.closeAssignment(assignment, "REVOKED",
                    "device offline before game start", true)) {
                notifyUser(account, "设备离线，任务已重新排队");
            }
            return;
        }

        var calibration = sklandCalibrationService.calibrate(account, now);
        var observedAfterAssignment = calibration
                .map(result -> result.getLastOnlineAt() != null
                        && !result.getLastOnlineAt().isBefore(assignment.getAssignedAt()))
                .orElse(false);
        if (!taskAssignmentService.closeAssignment(assignment, "REVOKED",
                observedAfterAssignment ? "device offline after game activity was observed"
                        : "device offline during task", false)) {
            return;
        }

        var requeueAt = observedAfterAssignment
                ? now.plusMinutes(STARTED_TASK_COOLDOWN_MINUTES) : now;
        requeue(account, assignment, requeueAt, now, observedAfterAssignment
                ? "设备离线，已校准游戏状态，10分钟后重新排队"
                : "设备离线，任务已重新排队");
    }

    private void requeue(AccountEntity account, TaskAssignmentEntity assignment,
                         LocalDateTime eligibleAt, LocalDateTime now, String message) {
        if (eligibleAt.isAfter(now)) {
            dynamicInfo.getFreezeUserInfoMap().put(account.getId(), eligibleAt);
            dynamicInfo.getCooldownReasonMap().put(account.getId(), "deviceOffline");
        } else {
            dynamicInfo.getFreezeUserInfoMap().remove(account.getId());
            dynamicInfo.getCooldownReasonMap().remove(account.getId());
        }
        dispatchQueueService.requeue(assignment);
        notifyUser(account, message);
    }

    private void notifyUser(AccountEntity account, String message) {
        try {
            messageService.push(account, "任务已回收", message);
        } catch (Exception exception) {
            log.warn("任务回收通知失败，账号 {}", account.getId(), exception);
        }
    }

    private void addHaltRequest(String deviceToken) {
        synchronized (dynamicInfo.getHaltList()) {
            if (!dynamicInfo.getHaltList().contains(deviceToken)) {
                dynamicInfo.getHaltList().add(deviceToken);
            }
        }
    }

    private boolean isGameStarted(TaskAssignmentEntity assignment) {
        return assignment.getGameStarted() != null && assignment.getGameStarted() == 1;
    }

    private boolean isDeleted(AccountEntity account) {
        return account.getDelete() != null && account.getDelete() == 1;
    }
}
