package moe.dazecake.inquisition.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import moe.dazecake.inquisition.mapper.LogMapper;
import moe.dazecake.inquisition.mapper.TaskAssignmentHistoryMapper;
import moe.dazecake.inquisition.model.entity.LogEntity;
import moe.dazecake.inquisition.model.entity.TaskAssignmentHistoryEntity;
import moe.dazecake.inquisition.utils.GameDayClock;
import moe.dazecake.inquisition.utils.GameLogClassifier;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Service
public class DailyLoginService {
    private static final String COMPLETED = "COMPLETED";
    private static final String DAILY = "daily";
    private static final String NORMAL = "NORMAL";

    @Resource
    LogMapper logMapper;

    @Resource
    TaskAssignmentHistoryMapper taskAssignmentHistoryMapper;

    public Map<Long, Integer> getLoginCounts(Collection<Long> requestedAccountIds, LocalDateTime now) {
        var counts = new HashMap<Long, Integer>();
        if (requestedAccountIds == null || requestedAccountIds.isEmpty()) {
            return counts;
        }

        Set<Long> accountIds = new HashSet<>();
        requestedAccountIds.forEach(accountId -> {
            if (accountId != null) {
                accountIds.add(accountId);
            }
        });
        if (accountIds.isEmpty()) {
            return counts;
        }

        var gameDayStart = GameDayClock.startOfGameDay(now);
        var logs = logMapper.selectList(Wrappers.<LogEntity>lambdaQuery()
                .in(LogEntity::getAccountId, accountIds)
                .ge(LogEntity::getTime, gameDayStart)
                .eq(LogEntity::getDelete, 0)
                .eq(LogEntity::getLevel, "INFO")
                .ne(LogEntity::getFrom, "SYSTEM")
                .like(LogEntity::getTitle, "\u767b\u5f55\u6210\u529f"));
        var countedAssignments = new HashMap<Long, Set<String>>();
        for (LogEntity log : logs) {
            if (!isCountableSuccessfulLogin(log, accountIds, gameDayStart)) {
                continue;
            }
            var assignmentId = log.getAssignmentId();
            if (assignmentId != null && !assignmentId.isBlank()
                    && !countedAssignments.computeIfAbsent(log.getAccountId(), ignored -> new HashSet<>())
                    .add(assignmentId)) {
                continue;
            }
            counts.merge(log.getAccountId(), 1, Integer::sum);
        }
        var completedDailyAssignments = taskAssignmentHistoryMapper.selectList(
                Wrappers.<TaskAssignmentHistoryEntity>lambdaQuery()
                        .in(TaskAssignmentHistoryEntity::getAccountId, accountIds)
                        .eq(TaskAssignmentHistoryEntity::getStatus, COMPLETED)
                        .eq(TaskAssignmentHistoryEntity::getTaskType, DAILY)
                        .eq(TaskAssignmentHistoryEntity::getTaskMode, NORMAL)
                        .ge(TaskAssignmentHistoryEntity::getFinishedAt, gameDayStart));
        for (TaskAssignmentHistoryEntity assignment : completedDailyAssignments) {
            if (!isCountableCompletedDaily(assignment, accountIds, gameDayStart)) {
                continue;
            }
            if (!countedAssignments.computeIfAbsent(assignment.getAccountId(), ignored -> new HashSet<>())
                    .add(assignment.getAssignmentId())) {
                continue;
            }
            counts.merge(assignment.getAccountId(), 1, Integer::sum);
        }
        return counts;
    }

    private boolean isCountableSuccessfulLogin(LogEntity log, Set<Long> accountIds,
                                               LocalDateTime gameDayStart) {
        return log != null
                && accountIds.contains(log.getAccountId())
                && log.getTime() != null
                && !log.getTime().isBefore(gameDayStart)
                && Integer.valueOf(0).equals(log.getDelete())
                && "INFO".equalsIgnoreCase(log.getLevel())
                && log.getFrom() != null
                && !log.getFrom().isBlank()
                && !"SYSTEM".equalsIgnoreCase(log.getFrom())
                && GameLogClassifier.isSuccessfulLoginLog(log.getTitle());
    }

    private boolean isCountableCompletedDaily(TaskAssignmentHistoryEntity assignment,
                                              Set<Long> accountIds,
                                              LocalDateTime gameDayStart) {
        return assignment != null
                && accountIds.contains(assignment.getAccountId())
                && assignment.getAssignmentId() != null
                && !assignment.getAssignmentId().isBlank()
                && COMPLETED.equals(assignment.getStatus())
                && DAILY.equals(assignment.getTaskType())
                && NORMAL.equals(assignment.getTaskMode())
                && assignment.getFinishedAt() != null
                && !assignment.getFinishedAt().isBefore(gameDayStart);
    }
}
