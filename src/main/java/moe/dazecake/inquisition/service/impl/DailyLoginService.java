package moe.dazecake.inquisition.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import moe.dazecake.inquisition.mapper.LogMapper;
import moe.dazecake.inquisition.model.entity.LogEntity;
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

    @Resource
    LogMapper logMapper;

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
}
