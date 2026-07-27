package moe.dazecake.inquisition.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.AllArgsConstructor;
import lombok.Getter;
import moe.dazecake.inquisition.mapper.AccountMapper;
import moe.dazecake.inquisition.mapper.LogMapper;
import moe.dazecake.inquisition.model.entity.AccountEntity;
import moe.dazecake.inquisition.model.entity.LogEntity;
import moe.dazecake.inquisition.model.entity.TaskAssignmentEntity;
import moe.dazecake.inquisition.utils.DynamicInfo;
import moe.dazecake.inquisition.utils.GameDayClock;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class FinalLoginSweepService {
    public static final String JOB_LOG_TITLE = "[审判庭] 26点最终补登扫描完成";
    private static final LocalTime FINAL_SWEEP_TIME = LocalTime.of(2, 0);
    private static final String DAILY_TASK_TYPE = "daily";
    private static final int MAX_LOG_DETAIL_LENGTH = 255;

    @Resource
    AccountMapper accountMapper;

    @Resource
    LogMapper logMapper;

    @Resource
    DailyLoginService dailyLoginService;

    @Resource
    TaskAssignmentService taskAssignmentService;

    @Resource
    UrgentTaskService urgentTaskService;

    @Resource
    MessageServiceImpl messageService;

    @Resource
    LogServiceImpl logService;

    @Resource
    DynamicInfo dynamicInfo;

    private LocalDate completedGameDay;

    public synchronized SweepResult runIfDue(LocalDateTime requestedNow) {
        var now = requestedNow == null ? GameDayClock.now() : requestedNow;
        var gameDay = GameDayClock.gameDay(now);
        var scheduledAt = LocalDateTime.of(gameDay.plusDays(1), FINAL_SWEEP_TIME);
        if (now.isBefore(scheduledAt) || gameDay.equals(completedGameDay)) {
            return SweepResult.skipped(gameDay);
        }
        if (hasCompletedMarker(now)) {
            completedGameDay = gameDay;
            return SweepResult.skipped(gameDay);
        }

        var result = executeSweep(now, gameDay);
        completedGameDay = gameDay;
        return result;
    }

    private boolean hasCompletedMarker(LocalDateTime now) {
        var markerCount = logMapper.selectCount(Wrappers.<LogEntity>lambdaQuery()
                .eq(LogEntity::getDelete, 0)
                .eq(LogEntity::getFrom, "SYSTEM")
                .eq(LogEntity::getTitle, JOB_LOG_TITLE)
                .ge(LogEntity::getTime, GameDayClock.startOfGameDay(now)));
        return markerCount != null && markerCount > 0;
    }

    private SweepResult executeSweep(LocalDateTime now, LocalDate gameDay) {
        var selectedAccounts = accountMapper.selectList(Wrappers.<AccountEntity>lambdaQuery()
                .eq(AccountEntity::getDelete, 0)
                .eq(AccountEntity::getFreeze, 0)
                .eq(AccountEntity::getTaskType, DAILY_TASK_TYPE)
                .ge(AccountEntity::getExpireTime, now)
                .orderByAsc(AccountEntity::getId));
        var eligibleAccounts = selectedAccounts.stream()
                .filter(account -> isEligible(account, now))
                .collect(Collectors.toList());
        Set<Long> eligibleIds = eligibleAccounts.stream()
                .map(AccountEntity::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, Integer> loginCounts = dailyLoginService.getLoginCounts(eligibleIds, now);
        var missingAccounts = eligibleAccounts.stream()
                .filter(account -> loginCounts.getOrDefault(account.getId(), 0) < 1)
                .collect(Collectors.toList());

        Set<Long> runningIds = new HashSet<>();
        synchronized (dynamicInfo.getWorkUserList()) {
            runningIds.addAll(dynamicInfo.getWorkUserList());
        }
        taskAssignmentService.findAll().forEach(assignment -> {
            if (assignment != null && assignment.getAccountId() != null) {
                runningIds.add(assignment.getAccountId());
            }
        });
        Map<Long, LocalDateTime> cooldownUntilById = new HashMap<>();
        synchronized (dynamicInfo.getFreezeUserInfoMap()) {
            dynamicInfo.getFreezeUserInfoMap().forEach((accountId, until) -> {
                if (accountId != null && until != null && until.isAfter(now)) {
                    cooldownUntilById.put(accountId, until);
                }
            });
        }

        var queuedIds = new ArrayList<Long>();
        var runningCount = 0;
        var cooldownCount = 0;
        for (AccountEntity account : missingAccounts) {
            var status = UrgentTaskService.STATUS_WAITING;
            LocalDateTime nextRetryAt = null;
            if (runningIds.contains(account.getId())) {
                status = UrgentTaskService.STATUS_RUNNING;
                runningCount++;
            } else {
                queuedIds.add(account.getId());
                if (cooldownUntilById.containsKey(account.getId())) {
                    status = UrgentTaskService.STATUS_RETRY_WAIT;
                    nextRetryAt = cooldownUntilById.get(account.getId());
                    cooldownCount++;
                }
            }
            urgentTaskService.upsert(account.getId(), gameDay,
                    UrgentTaskService.TRIGGER_TWENTY_SIX, UrgentTaskService.MODE_LOGIN_ONLY,
                    UrgentTaskService.PRIORITY_TWENTY_SIX, status, nextRetryAt, now);
        }

        synchronized (dynamicInfo.getWaitUserList()) {
            dynamicInfo.getWaitUserList().removeIf(queuedIds::contains);
            dynamicInfo.getWaitUserList().addAll(0, queuedIds);
        }

        var summary = "游戏日: " + gameDay + "\n有效日常账号: " + eligibleAccounts.size()
                + "\n仍未登录: " + missingAccounts.size() + "\n等待加急: " + queuedIds.size()
                + "\n正在运行: " + runningCount + "\n重试等待: " + cooldownCount;
        logService.logInfo(JOB_LOG_TITLE, truncate(summary));
        if (!missingAccounts.isEmpty()) {
            messageService.pushAdmin("[审判庭] 26点最终补登：" + missingAccounts.size() + "个",
                    summary + "\n账号: " + missingAccounts.stream()
                            .map(this::displayName)
                            .collect(Collectors.joining(" / ")));
        }
        return new SweepResult(gameDay, true, eligibleAccounts.size(), missingAccounts.size(),
                runningCount, cooldownCount, queuedIds.size());
    }

    public int sendFailureSummary(LocalDateTime requestedNow) {
        var now = requestedNow == null ? GameDayClock.now() : requestedNow;
        var gameDay = GameDayClock.gameDay(now);
        var active = urgentTaskService.findActiveForGameDay(gameDay);
        if (active.isEmpty()) {
            return 0;
        }
        var accountIds = active.stream().map(task -> task.getAccountId())
                .filter(id -> id != null).collect(Collectors.toList());
        var accounts = accountMapper.selectBatchIds(accountIds);
        Map<Long, AccountEntity> accountById = new LinkedHashMap<>();
        accounts.forEach(account -> accountById.put(account.getId(), account));
        var lines = active.stream().map(task -> {
            var account = accountById.get(task.getAccountId());
            var name = account == null ? "账号" + task.getAccountId() : displayName(account);
            var error = task.getLastError() == null || task.getLastError().isBlank()
                    ? "等待可用设备" : task.getLastError();
            return name + "：" + error;
        }).collect(Collectors.toList());
        messageService.pushAdmin("[审判庭] 26点补登失败：" + active.size() + "个",
                "游戏日 " + gameDay + " 截至03:45仍未完成：\n" + String.join("\n", lines));
        return active.size();
    }

    public int cleanup(LocalDateTime requestedNow) {
        var now = requestedNow == null ? GameDayClock.now() : requestedNow;
        var currentGameDay = GameDayClock.gameDay(now);
        var assignments = taskAssignmentService.findAll();
        if (assignments != null) {
            assignments.stream()
                    .filter(assignment -> assignment != null
                            && UrgentTaskService.MODE_LOGIN_ONLY.equals(assignment.getTaskMode()))
                    .filter(assignment -> shouldCloseLoginOnlyAssignment(assignment, currentGameDay))
                    .forEach(assignment -> {
                        var deviceToken = assignment.getDeviceToken();
                        if (deviceToken != null && !deviceToken.isBlank()) {
                            synchronized (dynamicInfo.getHaltList()) {
                                if (!dynamicInfo.getHaltList().contains(deviceToken)) {
                                    dynamicInfo.getHaltList().add(deviceToken);
                                }
                            }
                        }
                        if (!taskAssignmentService.closeAssignment(assignment, "EXPIRED_GAME_DAY",
                                "twenty-six login window ended", true)) {
                            throw new IllegalStateException("Unable to close expired login-only assignment");
                        }
                    });
        }
        return urgentTaskService.cleanupBefore(currentGameDay);
    }

    private boolean shouldCloseLoginOnlyAssignment(TaskAssignmentEntity assignment, LocalDate currentGameDay) {
        if (assignment.getUrgentTaskId() == null) {
            return true;
        }
        var task = urgentTaskService.findById(assignment.getUrgentTaskId()).orElse(null);
        return task == null || task.getGameDay() == null || task.getGameDay().isBefore(currentGameDay)
                || !UrgentTaskService.isActiveStatus(task.getStatus());
    }

    private boolean isEligible(AccountEntity account, LocalDateTime now) {
        return account != null && account.getId() != null
                && Integer.valueOf(0).equals(account.getDelete())
                && Integer.valueOf(0).equals(account.getFreeze())
                && DAILY_TASK_TYPE.equals(account.getTaskType())
                && account.getExpireTime() != null && !account.getExpireTime().isBefore(now);
    }

    private String displayName(AccountEntity account) {
        if (account.getName() != null && !account.getName().isBlank()) {
            return account.getName();
        }
        return account.getAccount() == null ? String.valueOf(account.getId()) : account.getAccount();
    }

    private String truncate(String detail) {
        return detail.length() <= MAX_LOG_DETAIL_LENGTH ? detail : detail.substring(0, MAX_LOG_DETAIL_LENGTH);
    }

    @Getter
    @AllArgsConstructor
    public static final class SweepResult {
        private final LocalDate gameDay;
        private final boolean executed;
        private final int eligibleCount;
        private final int missingCount;
        private final int runningCount;
        private final int cooldownCount;
        private final int queuedCount;

        static SweepResult skipped(LocalDate gameDay) {
            return new SweepResult(gameDay, false, 0, 0, 0, 0, 0);
        }
    }
}
