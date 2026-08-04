package moe.dazecake.inquisition.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import moe.dazecake.inquisition.mapper.UrgentTaskMapper;
import moe.dazecake.inquisition.model.entity.UrgentTaskEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class UrgentTaskService {
    public static final String TRIGGER_TWENTY_SIX = "TWENTY_SIX";

    public static final String MODE_LOGIN_ONLY = "LOGIN_ONLY";

    public static final String STATUS_WAITING = "WAITING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_RETRY_WAIT = "RETRY_WAIT";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    public static final int PRIORITY_TWENTY_SIX = 100;

    private static final Set<String> ACTIVE_STATUSES = Set.of(
            STATUS_WAITING, STATUS_RUNNING, STATUS_RETRY_WAIT, STATUS_FAILED);

    @Resource
    UrgentTaskMapper urgentTaskMapper;

    @Transactional
    public UrgentTaskEntity upsert(Long accountId, LocalDate gameDay, String triggerType,
                                   String taskMode, int priority, String status,
                                   LocalDateTime nextRetryAt, LocalDateTime now) {
        if (accountId == null || gameDay == null || now == null) {
            throw new IllegalArgumentException("accountId, gameDay and now are required");
        }
        var existing = urgentTaskMapper.selectOne(Wrappers.<UrgentTaskEntity>lambdaQuery()
                .eq(UrgentTaskEntity::getAccountId, accountId)
                .eq(UrgentTaskEntity::getGameDay, gameDay));
        if (existing == null) {
            var created = new UrgentTaskEntity()
                    .setAccountId(accountId)
                    .setGameDay(gameDay)
                    .setTriggerType(triggerType)
                    .setTaskMode(taskMode)
                    .setPriority(priority)
                    .setStatus(status)
                    .setAttemptCount(0)
                    .setNextRetryAt(nextRetryAt)
                    .setCreatedAt(now)
                    .setUpdatedAt(now);
            if (urgentTaskMapper.insert(created) != 1) {
                throw new IllegalStateException("Unable to persist urgent task");
            }
            return created;
        }

        existing.setTriggerType(triggerType)
                .setTaskMode(taskMode)
                .setPriority(priority)
                .setStatus(status)
                .setNextRetryAt(nextRetryAt)
                .setUpdatedAt(now);
        if (!STATUS_RETRY_WAIT.equals(status) && !STATUS_FAILED.equals(status)) {
            existing.setLastError(null);
        }
        if (existing.getAttemptCount() == null) {
            existing.setAttemptCount(0);
        }
        update(existing);
        return existing;
    }

    public List<UrgentTaskEntity> findActiveForGameDay(LocalDate gameDay) {
        if (gameDay == null) {
            return new ArrayList<>();
        }
        var rows = urgentTaskMapper.selectList(Wrappers.<UrgentTaskEntity>lambdaQuery()
                .eq(UrgentTaskEntity::getGameDay, gameDay)
                .in(UrgentTaskEntity::getStatus, ACTIVE_STATUSES)
                .orderByDesc(UrgentTaskEntity::getPriority)
                .orderByAsc(UrgentTaskEntity::getCreatedAt));
        if (rows == null) {
            return new ArrayList<>();
        }
        return rows.stream()
                .filter(task -> task != null && gameDay.equals(task.getGameDay())
                        && isActiveStatus(task.getStatus()))
                .sorted(dispatchOrder())
                .collect(Collectors.toList());
    }

    public List<UrgentTaskEntity> findAllForGameDay(LocalDate gameDay) {
        if (gameDay == null) {
            return new ArrayList<>();
        }
        var rows = urgentTaskMapper.selectList(Wrappers.<UrgentTaskEntity>lambdaQuery()
                .eq(UrgentTaskEntity::getGameDay, gameDay)
                .orderByDesc(UrgentTaskEntity::getPriority)
                .orderByAsc(UrgentTaskEntity::getCreatedAt));
        if (rows == null) {
            return new ArrayList<>();
        }
        return rows.stream()
                .filter(task -> task != null && gameDay.equals(task.getGameDay()))
                .sorted(dispatchOrder())
                .collect(Collectors.toList());
    }

    public List<UrgentTaskEntity> findDispatchable(LocalDate gameDay, LocalDateTime now) {
        return findActiveForGameDay(gameDay).stream()
                .filter(task -> STATUS_WAITING.equals(task.getStatus())
                        || STATUS_RETRY_WAIT.equals(task.getStatus())
                        || STATUS_FAILED.equals(task.getStatus()))
                .filter(task -> task.getNextRetryAt() == null || !task.getNextRetryAt().isAfter(now))
                .sorted(dispatchOrder())
                .collect(Collectors.toList());
    }

    public Optional<UrgentTaskEntity> findActiveByAccount(Long accountId, LocalDate gameDay) {
        if (accountId == null || gameDay == null) {
            return Optional.empty();
        }
        var task = urgentTaskMapper.selectOne(Wrappers.<UrgentTaskEntity>lambdaQuery()
                .eq(UrgentTaskEntity::getAccountId, accountId)
                .eq(UrgentTaskEntity::getGameDay, gameDay)
                .in(UrgentTaskEntity::getStatus, ACTIVE_STATUSES));
        return task != null && isActiveStatus(task.getStatus()) ? Optional.of(task) : Optional.empty();
    }

    public Optional<UrgentTaskEntity> findActiveById(Long id) {
        return findById(id).filter(task -> isActiveStatus(task.getStatus()));
    }

    public Optional<UrgentTaskEntity> findById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        var task = urgentTaskMapper.selectById(id);
        return Optional.ofNullable(task);
    }

    @Transactional
    public void markRunning(UrgentTaskEntity task, LocalDateTime now) {
        if (task == null) {
            return;
        }
        task.setStatus(STATUS_RUNNING)
                .setAttemptCount((task.getAttemptCount() == null ? 0 : task.getAttemptCount()) + 1)
                .setNextRetryAt(null)
                .setLastError(null)
                .setUpdatedAt(now);
        update(task);
    }

    @Transactional
    public void markRetry(UrgentTaskEntity task, String error, LocalDateTime nextRetryAt, LocalDateTime now) {
        if (task == null) {
            return;
        }
        task.setStatus(STATUS_RETRY_WAIT)
                .setNextRetryAt(nextRetryAt)
                .setLastError(trim(error))
                .setUpdatedAt(now);
        update(task);
    }

    @Transactional
    public Optional<UrgentTaskEntity> completeForSuccessfulLogin(Long accountId, LocalDate gameDay,
                                                                 LocalDateTime now) {
        var active = findActiveByAccount(accountId, gameDay);
        if (active.isEmpty()) {
            return Optional.empty();
        }
        var task = active.get();
        task.setStatus(STATUS_SUCCEEDED)
                .setNextRetryAt(null)
                .setLastError(null)
                .setUpdatedAt(now);
        update(task);
        return Optional.of(task);
    }

    @Transactional
    public boolean retryNow(Long id, LocalDateTime now) {
        var task = urgentTaskMapper.selectById(id);
        if (task == null || !(STATUS_RETRY_WAIT.equals(task.getStatus())
                || STATUS_FAILED.equals(task.getStatus()))) {
            return false;
        }
        task.setStatus(STATUS_WAITING)
                .setNextRetryAt(null)
                .setLastError(null)
                .setUpdatedAt(now);
        update(task);
        return true;
    }

    @Transactional
    public boolean markWaiting(Long id, LocalDateTime now) {
        var task = urgentTaskMapper.selectById(id);
        if (task == null || !isActiveStatus(task.getStatus())) {
            return false;
        }
        task.setStatus(STATUS_WAITING)
                .setNextRetryAt(null)
                .setLastError(null)
                .setUpdatedAt(now);
        update(task);
        return true;
    }

    @Transactional
    public boolean cancel(Long id, LocalDateTime now) {
        var task = urgentTaskMapper.selectById(id);
        if (task == null || !isActiveStatus(task.getStatus())) {
            return false;
        }
        task.setStatus(STATUS_CANCELLED)
                .setNextRetryAt(null)
                .setUpdatedAt(now);
        update(task);
        return true;
    }

    @Transactional
    public int cleanupBefore(LocalDate gameDay) {
        return urgentTaskMapper.delete(Wrappers.<UrgentTaskEntity>lambdaQuery()
                .lt(UrgentTaskEntity::getGameDay, gameDay));
    }

    public static boolean isActiveStatus(String status) {
        return ACTIVE_STATUSES.contains(status);
    }

    private Comparator<UrgentTaskEntity> dispatchOrder() {
        return Comparator.comparing(UrgentTaskEntity::getPriority,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(UrgentTaskEntity::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(UrgentTaskEntity::getId,
                        Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private void update(UrgentTaskEntity task) {
        if (urgentTaskMapper.updateById(task) != 1) {
            throw new IllegalStateException("Unable to update urgent task");
        }
    }

    private String trim(String value) {
        if (value == null || value.length() <= 255) {
            return value;
        }
        return value.substring(0, 255);
    }
}
