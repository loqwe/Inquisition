package moe.dazecake.inquisition.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import moe.dazecake.inquisition.mapper.AccountMapper;
import moe.dazecake.inquisition.mapper.DeviceMapper;
import moe.dazecake.inquisition.model.entity.AccountEntity;
import moe.dazecake.inquisition.model.entity.DeviceEntity;
import moe.dazecake.inquisition.model.entity.TaskAssignmentEntity;
import moe.dazecake.inquisition.model.entity.UrgentTaskEntity;
import moe.dazecake.inquisition.model.vo.task.RunningTaskVO;
import moe.dazecake.inquisition.model.vo.task.TaskBoardAccountVO;
import moe.dazecake.inquisition.model.vo.task.TaskBoardSummaryVO;
import moe.dazecake.inquisition.model.vo.task.TaskBoardVO;
import moe.dazecake.inquisition.model.vo.task.UrgentTaskVO;
import moe.dazecake.inquisition.utils.DynamicInfo;
import moe.dazecake.inquisition.utils.GameDayClock;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class TaskBoardService {

    @Resource
    AccountMapper accountMapper;

    @Resource
    DeviceMapper deviceMapper;

    @Resource
    TaskAssignmentService taskAssignmentService;

    @Resource
    UrgentTaskService urgentTaskService;

    @Resource
    TaskServiceImpl taskService;

    @Resource
    DynamicInfo dynamicInfo;

    @Resource
    DispatchQueueService dispatchQueueService;

    public TaskBoardVO getBoard(LocalDateTime requestedNow) {
        var now = requestedNow == null ? GameDayClock.now() : requestedNow;
        var gameDay = GameDayClock.gameDay(now);
        taskService.restoreExpiredCooldownTasks();

        var activeUrgent = urgentTaskService.findActiveForGameDay(gameDay);
        var allUrgent = urgentTaskService.findAllForGameDay(gameDay);
        var assignments = taskAssignmentService.findAll();
        if (assignments == null) {
            assignments = new ArrayList<>();
        }
        List<Long> waitIds;
        synchronized (dynamicInfo.getWaitUserList()) {
            waitIds = new ArrayList<>(dynamicInfo.getWaitUserList());
        }

        var accountIds = new LinkedHashSet<Long>();
        accountIds.addAll(waitIds);
        activeUrgent.forEach(task -> accountIds.add(task.getAccountId()));
        assignments.forEach(assignment -> accountIds.add(assignment.getAccountId()));
        Map<Long, AccountEntity> accountById = new HashMap<>();
        if (!accountIds.isEmpty()) {
            accountMapper.selectBatchIds(accountIds).forEach(account -> accountById.put(account.getId(), account));
        }
        var deviceTokens = assignments.stream()
                .map(TaskAssignmentEntity::getDeviceToken)
                .filter(Objects::nonNull)
                .filter(token -> !token.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, String> deviceNameByToken = new HashMap<>();
        if (!deviceTokens.isEmpty()) {
            deviceMapper.selectList(Wrappers.<DeviceEntity>lambdaQuery()
                            .in(DeviceEntity::getDeviceToken, deviceTokens))
                    .forEach(device -> deviceNameByToken.putIfAbsent(
                            device.getDeviceToken(), device.getDeviceName()));
        }

        Map<Long, UrgentTaskEntity> activeUrgentByAccount = activeUrgent.stream()
                .filter(task -> task.getAccountId() != null)
                .collect(Collectors.toMap(UrgentTaskEntity::getAccountId, task -> task, (left, right) -> left));
        Map<Long, TaskAssignmentEntity> assignmentByAccount = assignments.stream()
                .filter(assignment -> assignment.getAccountId() != null)
                .collect(Collectors.toMap(TaskAssignmentEntity::getAccountId, assignment -> assignment,
                        (left, right) -> left));
        Set<Long> returnedFromUrgent = allUrgent.stream()
                .filter(task -> UrgentTaskService.STATUS_SUCCEEDED.equals(task.getStatus()))
                .map(UrgentTaskEntity::getAccountId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        var urgentRows = activeUrgent.stream()
                .map(task -> urgentTask(task, accountById.get(task.getAccountId()),
                        assignmentByAccount.get(task.getAccountId())))
                .collect(Collectors.toList());

        var pendingRows = waitIds.stream()
                .filter(id -> !activeUrgentByAccount.containsKey(id))
                .map(accountById::get)
                .filter(Objects::nonNull)
                .map(account -> accountRow(account, returnedFromUrgent.contains(account.getId())))
                .collect(Collectors.toList());

        var runningRows = assignments.stream()
                .map(assignment -> runningTask(assignment, accountById.get(assignment.getAccountId()),
                        deviceNameByToken.get(assignment.getDeviceToken()),
                        activeUrgentByAccount.containsKey(assignment.getAccountId()), now))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(RunningTaskVO::getUrgent, Comparator.reverseOrder())
                        .thenComparing(RunningTaskVO::getAssignedAt,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(Collectors.toList());

        var cooldownRows = new ArrayList<>(taskService.getActiveCooldownTaskInfoMap().values());
        cooldownRows.sort(Comparator.comparing(item -> item.getUntil(),
                Comparator.nullsLast(Comparator.naturalOrder())));

        var frozenRows = accountMapper.selectList(Wrappers.<AccountEntity>lambdaQuery()
                        .eq(AccountEntity::getDelete, 0)
                        .eq(AccountEntity::getFreeze, 1)
                        .ge(AccountEntity::getExpireTime, now)
                        .orderByAsc(AccountEntity::getId)).stream()
                .map(account -> accountRow(account, false))
                .collect(Collectors.toList());

        var summary = new TaskBoardSummaryVO()
                .setUrgent(urgentRows.size())
                .setPending(pendingRows.size())
                .setInProgress(runningRows.size())
                .setCoolingDown(cooldownRows.size())
                .setFrozen(frozenRows.size());
        return new TaskBoardVO().setGeneratedAt(now).setSummary(summary)
                .setUrgentTasks(urgentRows).setPendingTasks(pendingRows)
                .setRunningTasks(runningRows).setCooldownTasks(cooldownRows).setFrozenTasks(frozenRows);
    }

    public boolean retryUrgentTask(Long urgentTaskId, LocalDateTime requestedNow) {
        var now = requestedNow == null ? GameDayClock.now() : requestedNow;
        var task = urgentTaskService.findActiveById(urgentTaskId).orElse(null);
        if (task == null || !urgentTaskService.retryNow(urgentTaskId, now)) {
            return false;
        }
        dynamicInfo.getFreezeUserInfoMap().remove(task.getAccountId());
        dynamicInfo.getCooldownReasonMap().remove(task.getAccountId());
        dispatchQueueService.enqueueUrgent(task.getAccountId(), now);
        return true;
    }

    public boolean cancelUrgentTask(Long urgentTaskId, LocalDateTime requestedNow) {
        var now = requestedNow == null ? GameDayClock.now() : requestedNow;
        var task = urgentTaskService.findActiveById(urgentTaskId).orElse(null);
        if (task == null || !urgentTaskService.cancel(urgentTaskId, now)) {
            return false;
        }
        var assignment = taskAssignmentService.findByAccount(task.getAccountId()).orElse(null);
        if (assignment != null && UrgentTaskService.MODE_LOGIN_ONLY.equals(assignment.getTaskMode())) {
            synchronized (dynamicInfo.getHaltList()) {
                if (!dynamicInfo.getHaltList().contains(assignment.getDeviceToken())) {
                    dynamicInfo.getHaltList().add(assignment.getDeviceToken());
                }
            }
            taskAssignmentService.closeAssignment(assignment, "CANCELLED",
                    "administrator cancelled twenty-six urgency", true);
        }
        dispatchQueueService.restoreBest(task.getAccountId(), now);
        return true;
    }

    private UrgentTaskVO urgentTask(UrgentTaskEntity task, AccountEntity account,
                                    TaskAssignmentEntity assignment) {
        return new UrgentTaskVO().setId(task.getId()).setAccountId(task.getAccountId())
                .setName(account == null ? "账号" + task.getAccountId() : account.getName())
                .setAccount(account == null ? null : account.getAccount())
                .setGameDay(task.getGameDay()).setTriggerType(task.getTriggerType())
                .setTaskMode(task.getTaskMode()).setStatus(assignment == null ? task.getStatus() : UrgentTaskService.STATUS_RUNNING)
                .setAttemptCount(task.getAttemptCount()).setNextRetryAt(task.getNextRetryAt())
                .setLastError(task.getLastError()).setCreatedAt(task.getCreatedAt()).setUpdatedAt(task.getUpdatedAt())
                .setDeviceToken(assignment == null ? null : assignment.getDeviceToken())
                .setAssignedAt(assignment == null ? null : assignment.getAssignedAt())
                .setLastProgressTitle(assignment == null ? null : assignment.getLastProgressTitle());
    }

    private TaskBoardAccountVO accountRow(AccountEntity account, boolean returnedFromUrgent) {
        return new TaskBoardAccountVO().setId(account.getId()).setName(account.getName())
                .setAccount(account.getAccount()).setTaskType(account.getTaskType()).setAgent(account.getAgent())
                .setExpireTime(account.getExpireTime()).setReturnedFromUrgent(returnedFromUrgent);
    }

    private RunningTaskVO runningTask(TaskAssignmentEntity assignment, AccountEntity account,
                                      String deviceName, boolean urgent, LocalDateTime now) {
        if (assignment == null || account == null) {
            return null;
        }
        var minutes = assignment.getAssignedAt() == null ? 0L
                : Math.max(0L, Duration.between(assignment.getAssignedAt(), now).toMinutes());
        return new RunningTaskVO().setAssignmentId(assignment.getAssignmentId())
                .setAccountId(account.getId()).setName(account.getName()).setAccount(account.getAccount())
                .setTaskType(account.getTaskType()).setTaskMode(assignment.getTaskMode())
                .setUrgent(urgent || UrgentTaskService.MODE_LOGIN_ONLY.equals(assignment.getTaskMode()))
                .setDeviceName(deviceName).setDeviceToken(assignment.getDeviceToken())
                .setAssignedAt(assignment.getAssignedAt())
                .setRunningMinutes(minutes).setLastProgressAt(assignment.getLastProgressAt())
                .setLastProgressTitle(assignment.getLastProgressTitle())
                .setLastProgressDetail(assignment.getLastProgressDetail())
                .setLeaseExpiresAt(assignment.getLeaseExpiresAt());
    }
}
