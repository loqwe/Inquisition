package moe.dazecake.inquisition.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import moe.dazecake.inquisition.mapper.TaskAssignmentHistoryMapper;
import moe.dazecake.inquisition.mapper.TaskAssignmentMapper;
import moe.dazecake.inquisition.model.entity.AccountEntity;
import moe.dazecake.inquisition.model.entity.TaskAssignmentEntity;
import moe.dazecake.inquisition.model.entity.TaskAssignmentHistoryEntity;
import moe.dazecake.inquisition.utils.DynamicInfo;
import moe.dazecake.inquisition.utils.GameDayClock;
import moe.dazecake.inquisition.utils.GameLogClassifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class TaskAssignmentService {
    public static final long HARD_LEASE_HOURS = 2;
    public static final String MODE_NORMAL = "NORMAL";

    @Resource
    TaskAssignmentMapper assignmentMapper;

    @Resource
    TaskAssignmentHistoryMapper historyMapper;

    @Resource
    DynamicInfo dynamicInfo;

    @Resource
    UrgentTaskService urgentTaskService;

    @Resource
    DispatchQueueService dispatchQueueService;

    @Transactional
    public TaskAssignmentEntity createAssignment(AccountEntity account, String deviceToken, LocalDateTime now) {
        return createAssignment(account, deviceToken, now, MODE_NORMAL, null);
    }

    @Transactional
    public TaskAssignmentEntity createAssignment(AccountEntity account, String deviceToken, LocalDateTime now,
                                                 String taskMode, Long urgentTaskId) {
        var assignment = new TaskAssignmentEntity()
                .setAssignmentId(UUID.randomUUID().toString())
                .setAccountId(account.getId())
                .setDeviceToken(deviceToken)
                .setTaskType(account.getTaskType())
                .setTaskMode(taskMode == null || taskMode.isBlank() ? MODE_NORMAL : taskMode)
                .setUrgentTaskId(urgentTaskId)
                .setAssignedAt(now)
                .setLeaseExpiresAt(now.plusHours(HARD_LEASE_HOURS))
                .setLastProgressAt(now)
                .setGameStarted(0)
                .setRetryCount(0);
        if (assignmentMapper.insert(assignment) != 1) {
            throw new IllegalStateException("Unable to persist task assignment");
        }
        dynamicInfo.addWorkUser(account.getId(), deviceToken, assignment.getLeaseExpiresAt(),
                assignment.getAssignmentId(), assignment.getAssignedAt(), assignment.getLastProgressAt(), false);
        return assignment;
    }

    public boolean matchesSubmission(TaskAssignmentEntity assignment, String deviceToken, String assignmentId) {
        if (assignment == null || !Objects.equals(assignment.getDeviceToken(), deviceToken)) {
            return false;
        }
        if (assignment.getLeaseExpiresAt() != null
                && !assignment.getLeaseExpiresAt().isAfter(GameDayClock.now())) {
            return false;
        }
        return assignmentId == null || assignmentId.isBlank()
                || Objects.equals(assignment.getAssignmentId(), assignmentId);
    }

    public Optional<TaskAssignmentEntity> findByDevice(String deviceToken) {
        if (deviceToken == null || deviceToken.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(assignmentMapper.selectOne(
                Wrappers.<TaskAssignmentEntity>lambdaQuery()
                        .eq(TaskAssignmentEntity::getDeviceToken, deviceToken)));
    }

    public Optional<TaskAssignmentEntity> findByAccount(Long accountId) {
        if (accountId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(assignmentMapper.selectOne(
                Wrappers.<TaskAssignmentEntity>lambdaQuery()
                        .eq(TaskAssignmentEntity::getAccountId, accountId)));
    }

    public List<TaskAssignmentEntity> findByDeviceForUpdate(String deviceToken) {
        return assignmentMapper.selectList(Wrappers.<TaskAssignmentEntity>lambdaQuery()
                .eq(TaskAssignmentEntity::getDeviceToken, deviceToken));
    }

    public List<TaskAssignmentEntity> findExpired(LocalDateTime now) {
        return assignmentMapper.selectList(Wrappers.<TaskAssignmentEntity>lambdaQuery()
                .le(TaskAssignmentEntity::getLeaseExpiresAt, now));
    }

    public List<TaskAssignmentEntity> findLongRunning(LocalDateTime now, long minutes) {
        return assignmentMapper.selectList(Wrappers.<TaskAssignmentEntity>lambdaQuery()
                .le(TaskAssignmentEntity::getAssignedAt, now.minusMinutes(minutes))
                .eq(TaskAssignmentEntity::getLongTaskNotified, 0));
    }

    public boolean markLongTaskNotified(TaskAssignmentEntity assignment) {
        if (assignment == null) {
            return false;
        }
        assignment.setLongTaskNotified(1);
        return assignmentMapper.updateById(assignment) == 1;
    }

    public List<TaskAssignmentEntity> findAll() {
        return assignmentMapper.selectList(null);
    }

    public int restoreActiveAssignments(LocalDateTime now) {
        var activeAssignments = assignmentMapper.selectList(Wrappers.<TaskAssignmentEntity>lambdaQuery()
                .gt(TaskAssignmentEntity::getLeaseExpiresAt, now));
        for (TaskAssignmentEntity assignment : activeAssignments) {
            if (assignment.getAccountId() == null || assignment.getDeviceToken() == null) {
                continue;
            }
            dynamicInfo.addWorkUser(assignment.getAccountId(), assignment.getDeviceToken(),
                    assignment.getLeaseExpiresAt(), assignment.getAssignmentId(), assignment.getAssignedAt(),
                    assignment.getLastProgressAt(), Objects.equals(assignment.getGameStarted(), 1));
        }
        return activeAssignments.size();
    }

    @Transactional
    public int closeExpiredAssignments(LocalDateTime now) {
        var expiredAssignments = findExpired(now);
        var closed = 0;
        for (TaskAssignmentEntity assignment : expiredAssignments) {
            if (assignment.getDeviceToken() != null && !assignment.getDeviceToken().isBlank()) {
                synchronized (dynamicInfo.getHaltList()) {
                    if (!dynamicInfo.getHaltList().contains(assignment.getDeviceToken())) {
                        dynamicInfo.getHaltList().add(assignment.getDeviceToken());
                    }
                }
            }
            if (closeAssignment(assignment, "TIMED_OUT", "two hour limit", true)) {
                closed++;
            }
        }
        return closed;
    }

    @Transactional
    public boolean revokeDeviceAssignment(String deviceToken, String reason, boolean requeue) {
        var assignment = findByDevice(deviceToken).orElse(null);
        return assignment != null && closeAssignment(assignment, "REVOKED", reason, requeue);
    }

    public boolean recordProgress(String deviceToken, String assignmentId, String level,
                                  String title, String detail) {
        var assignment = findByDevice(deviceToken).orElse(null);
        if (!matchesSubmission(assignment, deviceToken, assignmentId)
                || !GameLogClassifier.isValidGameLog(level, title, deviceToken)) {
            return false;
        }
        var now = GameDayClock.now();
        assignment.setLastProgressAt(now)
                .setLastProgressTitle(title)
                .setLastProgressDetail(detail);
        if (GameLogClassifier.isGameStarted(title)) {
            assignment.setGameStarted(1);
        }
        if (assignmentMapper.updateById(assignment) != 1) {
            return false;
        }
        var workUser = dynamicInfo.getWorkUserInfoMap().get(assignment.getAccountId());
        if (workUser != null) {
            workUser.setLastProgressAt(now);
            workUser.setGameStarted(Objects.equals(assignment.getGameStarted(), 1));
        }
        return true;
    }

    @Transactional
    public boolean closeAssignment(TaskAssignmentEntity assignment, String status, String reason, boolean requeue) {
        if (assignment == null || assignmentMapper.deleteById(assignment.getAssignmentId()) != 1) {
            return false;
        }
        var closedAt = GameDayClock.now();
        var history = new TaskAssignmentHistoryEntity()
                .setAssignmentId(assignment.getAssignmentId())
                .setAccountId(assignment.getAccountId())
                .setDeviceToken(assignment.getDeviceToken())
                .setTaskType(assignment.getTaskType())
                .setTaskMode(assignment.getTaskMode())
                .setUrgentTaskId(assignment.getUrgentTaskId())
                .setStatus(status)
                .setAssignedAt(assignment.getAssignedAt())
                .setLeaseExpiresAt(assignment.getLeaseExpiresAt())
                .setLastProgressAt(assignment.getLastProgressAt())
                .setGameStarted(assignment.getGameStarted())
                .setLastProgressTitle(assignment.getLastProgressTitle())
                .setLastProgressDetail(assignment.getLastProgressDetail())
                .setRetryCount(assignment.getRetryCount())
                .setLongTaskNotified(assignment.getLongTaskNotified())
                .setReason(reason)
                .setFinishedAt(closedAt);
        if (historyMapper.insert(history) != 1) {
            throw new IllegalStateException("Unable to archive task assignment");
        }
        dynamicInfo.removeWorkUser(assignment.getAccountId());
        if (requeue) {
            if (assignment.getUrgentTaskId() != null) {
                urgentTaskService.markWaiting(assignment.getUrgentTaskId(), closedAt);
            } else {
                urgentTaskService.findActiveByAccount(
                                assignment.getAccountId(), GameDayClock.gameDay(closedAt))
                        .filter(task -> UrgentTaskService.STATUS_RUNNING.equals(task.getStatus()))
                        .ifPresent(task -> urgentTaskService.markWaiting(task.getId(), closedAt));
            }
            dispatchQueueService.requeue(assignment);
        }
        return true;
    }
}
