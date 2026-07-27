package moe.dazecake.inquisition.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.AllArgsConstructor;
import lombok.Getter;
import moe.dazecake.inquisition.mapper.AccountMapper;
import moe.dazecake.inquisition.mapper.LogMapper;
import moe.dazecake.inquisition.model.entity.AccountEntity;
import moe.dazecake.inquisition.model.entity.LogEntity;
import moe.dazecake.inquisition.utils.DynamicInfo;
import moe.dazecake.inquisition.utils.GameDayClock;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class DailyLoginSweepService {
    public static final String JOB_LOG_TITLE = "[\u5ba1\u5224\u5ead] 14\u70b9\u8865\u767b\u626b\u63cf\u5b8c\u6210";
    private static final String DAILY_TASK_TYPE = "daily";
    private static final LocalTime SWEEP_TIME = LocalTime.of(14, 0);
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
    MessageServiceImpl messageService;

    @Resource
    LogServiceImpl logService;

    @Resource
    DynamicInfo dynamicInfo;

    private LocalDate completedGameDay;

    public synchronized SweepResult runIfDue(LocalDateTime requestedNow) {
        var now = requestedNow == null ? GameDayClock.now() : requestedNow;
        var gameDay = GameDayClock.gameDay(now);
        var scheduledAt = LocalDateTime.of(gameDay, SWEEP_TIME);
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
        var eligibleAccounts = new ArrayList<AccountEntity>();
        selectedAccounts.forEach(account -> {
            if (isEligible(account, now)) {
                eligibleAccounts.add(account);
            }
        });

        Set<Long> eligibleIds = new LinkedHashSet<>();
        eligibleAccounts.forEach(account -> eligibleIds.add(account.getId()));
        Map<Long, Integer> loginCounts = dailyLoginService.getLoginCounts(eligibleIds, now);
        var missingAccounts = new ArrayList<AccountEntity>();
        eligibleAccounts.forEach(account -> {
            if (loginCounts.getOrDefault(account.getId(), 0) < 1) {
                missingAccounts.add(account);
            }
        });

        Set<Long> runningIds;
        synchronized (dynamicInfo.getWorkUserList()) {
            runningIds = new HashSet<>(dynamicInfo.getWorkUserList());
        }
        taskAssignmentService.findAll().forEach(assignment -> {
            if (assignment != null && assignment.getAccountId() != null) {
                runningIds.add(assignment.getAccountId());
            }
        });
        Set<Long> cooldownIds = new HashSet<>();
        synchronized (dynamicInfo.getFreezeUserInfoMap()) {
            dynamicInfo.getFreezeUserInfoMap().forEach((accountId, until) -> {
                if (accountId != null && until != null && until.isAfter(now)) {
                    cooldownIds.add(accountId);
                }
            });
        }

        var priorityIds = new ArrayList<Long>();
        var priorityNames = new ArrayList<String>();
        var missingNames = new ArrayList<String>();
        var runningCount = 0;
        var cooldownCount = 0;
        for (AccountEntity account : missingAccounts) {
            missingNames.add(displayName(account));
            if (runningIds.contains(account.getId())) {
                runningCount++;
                continue;
            }
            if (cooldownIds.contains(account.getId())) {
                cooldownCount++;
                continue;
            }
            priorityIds.add(account.getId());
            priorityNames.add(displayName(account));
        }

        var newlyQueuedCount = 0;
        var reprioritizedCount = 0;
        synchronized (dynamicInfo.getWaitUserList()) {
            var waitingBefore = new HashSet<>(dynamicInfo.getWaitUserList());
            for (Long accountId : priorityIds) {
                if (waitingBefore.contains(accountId)) {
                    reprioritizedCount++;
                } else {
                    newlyQueuedCount++;
                }
            }
            dynamicInfo.getWaitUserList().removeIf(priorityIds::contains);
            dynamicInfo.getWaitUserList().addAll(0, priorityIds);
        }

        var summary = buildSummary(gameDay, eligibleAccounts.size(), missingAccounts.size(), priorityIds.size(),
                newlyQueuedCount, reprioritizedCount, runningCount, cooldownCount);
        logService.logInfo(JOB_LOG_TITLE, truncateLogDetail(summary));
        if (!missingAccounts.isEmpty()) {
            messageService.pushAdmin("[\u5ba1\u5224\u5ead] 14\u70b9\u8865\u767b\uff1a" + missingAccounts.size() + "\u4e2a",
                    buildNotificationDetail(summary, missingNames, priorityNames));
        }
        return new SweepResult(gameDay, true, eligibleAccounts.size(), missingAccounts.size(), priorityIds.size(),
                runningCount, cooldownCount, newlyQueuedCount, reprioritizedCount);
    }

    private boolean isEligible(AccountEntity account, LocalDateTime now) {
        return account != null
                && account.getId() != null
                && Integer.valueOf(0).equals(account.getDelete())
                && Integer.valueOf(0).equals(account.getFreeze())
                && DAILY_TASK_TYPE.equals(account.getTaskType())
                && account.getExpireTime() != null
                && !account.getExpireTime().isBefore(now);
    }

    private String displayName(AccountEntity account) {
        if (account.getName() != null && !account.getName().isBlank()) {
            return account.getName();
        }
        if (account.getAccount() != null && !account.getAccount().isBlank()) {
            return account.getAccount();
        }
        return String.valueOf(account.getId());
    }

    private String buildSummary(LocalDate gameDay, int eligibleCount, int missingCount, int prioritizedCount,
                                int newlyQueuedCount, int reprioritizedCount, int runningCount, int cooldownCount) {
        var detail = new StringBuilder();
        detail.append("\u6e38\u620f\u65e5: ").append(gameDay.format(DateTimeFormatter.ISO_LOCAL_DATE)).append('\n')
                .append("\u6709\u6548\u65e5\u5e38\u8d26\u53f7: ").append(eligibleCount).append('\n')
                .append("\u4eca\u65e5\u5df2\u767b\u5f55: ").append(eligibleCount - missingCount).append('\n')
                .append("\u9700\u8865\u767b: ").append(missingCount).append('\n')
                .append("\u4f18\u5148\u5165\u961f: ").append(prioritizedCount).append('\n')
                .append("\u65b0\u5165\u961f: ").append(newlyQueuedCount).append('\n')
                .append("\u5df2\u5728\u961f\u5217\u5e76\u63d0\u524d: ").append(reprioritizedCount).append('\n')
                .append("\u5df2\u5728\u8fd0\u884c: ").append(runningCount).append('\n')
                .append("\u51b7\u5374\u4e2d: ").append(cooldownCount);
        return detail.toString();
    }

    private String buildNotificationDetail(String summary, List<String> missingNames, List<String> priorityNames) {
        var detail = new StringBuilder(summary);
        if (!missingNames.isEmpty()) {
            detail.append("\n\u9700\u8865\u767b\u8d26\u53f7: ").append(String.join(" / ", missingNames));
        }
        if (!priorityNames.isEmpty()) {
            detail.append("\n\u4f18\u5148\u961f\u5217: ").append(String.join(" / ", priorityNames));
        }
        return detail.toString();
    }

    private String truncateLogDetail(String detail) {
        return detail.length() <= MAX_LOG_DETAIL_LENGTH
                ? detail
                : detail.substring(0, MAX_LOG_DETAIL_LENGTH);
    }

    @Getter
    @AllArgsConstructor
    public static final class SweepResult {
        private final LocalDate gameDay;
        private final boolean executed;
        private final int eligibleCount;
        private final int missingCount;
        private final int prioritizedCount;
        private final int runningCount;
        private final int cooldownCount;
        private final int newlyQueuedCount;
        private final int reprioritizedCount;

        static SweepResult skipped(LocalDate gameDay) {
            return new SweepResult(gameDay, false, 0, 0, 0, 0, 0, 0, 0);
        }
    }
}
