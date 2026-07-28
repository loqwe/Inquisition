package moe.dazecake.inquisition.service.impl;

import moe.dazecake.inquisition.mapper.AccountMapper;
import moe.dazecake.inquisition.model.entity.AccountEntity;
import moe.dazecake.inquisition.model.entity.AccountScheduledRunEntity;
import moe.dazecake.inquisition.model.entity.TaskAssignmentEntity;
import moe.dazecake.inquisition.model.entity.UrgentTaskEntity;
import moe.dazecake.inquisition.model.local.DispatchIntent;
import moe.dazecake.inquisition.utils.DynamicInfo;
import moe.dazecake.inquisition.utils.GameDayClock;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class DispatchQueueService {

    @Resource
    DynamicInfo dynamicInfo;

    @Resource
    AccountMapper accountMapper;

    @Resource
    AccountDispatchConfigService configService;

    @Resource
    AccountScheduledRunService runService;

    @Resource
    UrgentTaskService urgentTaskService;

    private final Map<Long, DispatchIntent> queuedIntents = new HashMap<>();
    private final Map<Long, DispatchIntent> manualIntents = new HashMap<>();

    public boolean enqueueAuto(Long accountId) {
        var now = GameDayClock.now();
        if (!isEligible(accountId, now) || !configService.isAuto(accountId)) {
            return false;
        }
        return enqueue(DispatchIntent.auto(accountId, now));
    }

    public boolean enqueueManual(Long accountId) {
        return enqueueManual(accountId, GameDayClock.now());
    }

    public boolean enqueueManual(Long accountId, LocalDateTime now) {
        if (!isEligible(accountId, now)) {
            return false;
        }
        var activeRun = runService.findActiveByAccount(accountId).orElse(null);
        if (activeRun != null) {
            if (AccountScheduledRunService.STATUS_RUNNING.equals(activeRun.getStatus())) {
                return false;
            }
            if (AccountScheduledRunService.STATUS_RETRY_WAIT.equals(activeRun.getStatus())) {
                if (!runService.retryNow(activeRun.getId())) {
                    return false;
                }
                activeRun.setStatus(AccountScheduledRunService.STATUS_WAITING)
                        .setNextRetryAt(null)
                        .setLastError(null);
            }
            return enqueueScheduled(activeRun, now);
        }
        var intent = DispatchIntent.manual(accountId, now);
        synchronized (dynamicInfo.getWaitUserList()) {
            manualIntents.put(accountId, intent);
        }
        return enqueue(intent) || contains(accountId);
    }

    public boolean enqueueScheduled(AccountScheduledRunEntity run) {
        return enqueueScheduled(run, GameDayClock.now());
    }

    public boolean enqueueUrgent(Long accountId) {
        return enqueueUrgent(accountId, GameDayClock.now());
    }

    public boolean enqueueUrgent(Long accountId, LocalDateTime now) {
        if (!isEligible(accountId, now)) {
            return false;
        }
        var task = urgentTaskService.findActiveByAccount(
                accountId, GameDayClock.gameDay(now)).orElse(null);
        if (!isDispatchable(task, now)) {
            return false;
        }
        return enqueue(DispatchIntent.urgent(accountId, task.getId(),
                firstNonNull(task.getCreatedAt(), now)));
    }

    public void requeue(TaskAssignmentEntity assignment) {
        if (assignment == null || assignment.getAccountId() == null) {
            return;
        }
        var source = assignment.getDispatchSource();
        if (DispatchIntent.SOURCE_URGENT_26.equals(source)
                || assignment.getUrgentTaskId() != null) {
            var now = GameDayClock.now();
            if (!enqueueUrgent(assignment.getAccountId(), now)) {
                restoreBest(assignment.getAccountId(), now);
            }
            return;
        }
        if (DispatchIntent.SOURCE_SCHEDULED.equals(source)) {
            runService.findById(assignment.getScheduledRunId()).ifPresent(this::enqueueScheduled);
            return;
        }
        if (DispatchIntent.SOURCE_MANUAL.equals(source)) {
            var now = GameDayClock.now();
            if (isEligible(assignment.getAccountId(), now)) {
                var intent = DispatchIntent.manual(assignment.getAccountId(), now);
                synchronized (dynamicInfo.getWaitUserList()) {
                    manualIntents.put(assignment.getAccountId(), intent);
                }
                enqueue(intent);
            }
            return;
        }
        enqueueAuto(assignment.getAccountId());
    }

    public void remove(Long accountId) {
        if (accountId == null) {
            return;
        }
        var queue = dynamicInfo.getWaitUserList();
        synchronized (queue) {
            queue.removeIf(accountId::equals);
            queuedIntents.remove(accountId);
            manualIntents.remove(accountId);
        }
    }

    public void dequeue(DispatchIntent intent) {
        if (intent == null || intent.getAccountId() == null) {
            return;
        }
        removeQueueEntry(intent.getAccountId());
        if (DispatchIntent.SOURCE_MANUAL.equals(intent.getSource())) {
            synchronized (dynamicInfo.getWaitUserList()) {
                manualIntents.remove(intent.getAccountId());
            }
        }
    }

    public List<Long> snapshot() {
        var queue = dynamicInfo.getWaitUserList();
        synchronized (queue) {
            return new ArrayList<>(queue);
        }
    }

    public boolean contains(Long accountId) {
        if (accountId == null) {
            return false;
        }
        var queue = dynamicInfo.getWaitUserList();
        synchronized (queue) {
            return queue.contains(accountId);
        }
    }

    public List<Long> promoteAutos(List<Long> accountIds, LocalDateTime now) {
        Objects.requireNonNull(now, "now");
        if (accountIds == null || accountIds.isEmpty()) {
            return List.of();
        }
        var promotable = new ArrayList<Long>();
        new LinkedHashSet<>(accountIds).forEach(accountId -> {
            var intent = resolve(accountId, now);
            if (intent != null && DispatchIntent.SOURCE_AUTO.equals(intent.getSource())) {
                promotable.add(accountId);
            }
        });
        var queue = dynamicInfo.getWaitUserList();
        synchronized (queue) {
            queue.removeIf(promotable::contains);
            promotable.forEach(accountId -> queuedIntents.put(
                    accountId, DispatchIntent.auto(accountId, now)));
            var insertAt = 0;
            while (insertAt < queue.size()
                    && priorityOf(queue.get(insertAt)) > DispatchIntent.PRIORITY_AUTO) {
                insertAt++;
            }
            queue.addAll(insertAt, promotable);
        }
        return new ArrayList<>(promotable);
    }

    public void replaceAutos(List<Long> accountIds, LocalDateTime now) {
        Objects.requireNonNull(now, "now");
        var queue = dynamicInfo.getWaitUserList();
        synchronized (queue) {
            var autoIds = new ArrayList<Long>();
            queue.forEach(accountId -> {
                if (priorityOf(accountId) == DispatchIntent.PRIORITY_AUTO) {
                    autoIds.add(accountId);
                }
            });
            queue.removeIf(autoIds::contains);
            autoIds.forEach(queuedIntents::remove);
        }
        if (accountIds != null) {
            new LinkedHashSet<>(accountIds).forEach(accountId -> enqueueAuto(accountId, now));
        }
    }

    public void restoreBest(Long accountId, LocalDateTime now) {
        var intent = resolve(accountId, now);
        removeQueueEntry(accountId);
        if (intent != null) {
            enqueue(intent);
        }
    }

    public int enqueueScheduledRuns(List<AccountScheduledRunEntity> runs, LocalDateTime now) {
        if (runs == null || runs.isEmpty()) {
            return 0;
        }
        var admitted = 0;
        for (var run : runs) {
            if (enqueueScheduled(run, now)) {
                admitted++;
            }
        }
        return admitted;
    }

    public void deduplicate() {
        var queue = dynamicInfo.getWaitUserList();
        synchronized (queue) {
            var unique = new LinkedHashSet<>(queue);
            queue.clear();
            queue.addAll(unique);
        }
    }

    public void reconcileRestoredQueue(LocalDateTime now) {
        reconcileRestoredQueue(now, true);
    }

    public void reconcileRestoredQueue(LocalDateTime now, boolean includeScheduled) {
        Objects.requireNonNull(now, "now");
        var queue = dynamicInfo.getWaitUserList();
        var restoredIds = new ArrayList<Long>();
        synchronized (queue) {
            restoredIds.addAll(new LinkedHashSet<>(queue));
            queue.clear();
            queuedIntents.clear();
            manualIntents.clear();
        }
        restoredIds.forEach(accountId -> enqueueAuto(accountId, now));
        if (includeScheduled) {
            var scheduledRuns = runService.findDispatchable(now);
            if (scheduledRuns != null) {
                scheduledRuns.forEach(run -> enqueueScheduled(run, now));
            }
        }
        var urgentTasks = urgentTaskService.findDispatchable(GameDayClock.gameDay(now), now);
        if (urgentTasks != null) {
            urgentTasks.forEach(task -> enqueueUrgent(task, now));
        }
    }

    public DispatchIntent resolve(Long accountId, LocalDateTime now) {
        if (accountId == null || now == null || !isEligible(accountId, now)) {
            return null;
        }
        var urgentTask = urgentTaskService.findActiveByAccount(
                accountId, GameDayClock.gameDay(now)).orElse(null);
        if (isDispatchable(urgentTask, now)) {
            return DispatchIntent.urgent(accountId, urgentTask.getId(),
                    firstNonNull(urgentTask.getCreatedAt(), now));
        }
        var scheduledRun = runService.findActiveByAccount(accountId).orElse(null);
        if (isDispatchable(scheduledRun, now)) {
            return DispatchIntent.scheduled(accountId, scheduledRun.getId(),
                    firstNonNull(scheduledRun.getScheduledFor(), now));
        }
        synchronized (dynamicInfo.getWaitUserList()) {
            var manual = manualIntents.get(accountId);
            if (manual != null) {
                return manual;
            }
            var queued = queuedIntents.get(accountId);
            if (configService.isAuto(accountId)) {
                return queued != null && DispatchIntent.SOURCE_AUTO.equals(queued.getSource())
                        ? queued
                        : DispatchIntent.auto(accountId, now);
            }
        }
        return null;
    }

    public boolean enqueueAuto(Long accountId, LocalDateTime now) {
        if (!isEligible(accountId, now) || !configService.isAuto(accountId)) {
            return false;
        }
        return enqueue(DispatchIntent.auto(accountId, now));
    }

    public boolean enqueueScheduled(AccountScheduledRunEntity run, LocalDateTime now) {
        if (run == null || run.getId() == null || run.getAccountId() == null
                || !isEligible(run.getAccountId(), now)) {
            return false;
        }
        if (AccountScheduledRunService.STATUS_RETRY_WAIT.equals(run.getStatus())) {
            if (run.getNextRetryAt() != null && run.getNextRetryAt().isAfter(now)) {
                return false;
            }
            if (!runService.markWaiting(run.getId())) {
                return false;
            }
            run.setStatus(AccountScheduledRunService.STATUS_WAITING)
                    .setNextRetryAt(null)
                    .setLastError(null);
        }
        if (!AccountScheduledRunService.STATUS_WAITING.equals(run.getStatus())) {
            return false;
        }
        synchronized (dynamicInfo.getWaitUserList()) {
            manualIntents.remove(run.getAccountId());
        }
        return enqueue(DispatchIntent.scheduled(run.getAccountId(), run.getId(),
                firstNonNull(run.getScheduledFor(), now)));
    }

    private boolean enqueueUrgent(UrgentTaskEntity task, LocalDateTime now) {
        if (task != null && task.getAccountId() != null && isEligible(task.getAccountId(), now)
                && isDispatchable(task, now)) {
            return enqueue(DispatchIntent.urgent(task.getAccountId(), task.getId(),
                    firstNonNull(task.getCreatedAt(), now)));
        }
        return false;
    }

    private void removeQueueEntry(Long accountId) {
        if (accountId == null) {
            return;
        }
        var queue = dynamicInfo.getWaitUserList();
        synchronized (queue) {
            queue.removeIf(accountId::equals);
            queuedIntents.remove(accountId);
        }
    }

    private boolean enqueue(DispatchIntent intent) {
        var queue = dynamicInfo.getWaitUserList();
        synchronized (queue) {
            var current = queuedIntents.get(intent.getAccountId());
            if (current != null) {
                if (current.getPriority() > intent.getPriority()) {
                    return false;
                }
                if (current.getPriority() == intent.getPriority()) {
                    if (Objects.equals(current.getSource(), intent.getSource())
                            || DispatchIntent.SOURCE_SCHEDULED.equals(current.getSource())) {
                        return false;
                    }
                    if (DispatchIntent.SOURCE_MANUAL.equals(current.getSource())
                            && DispatchIntent.SOURCE_SCHEDULED.equals(intent.getSource())) {
                        queuedIntents.put(intent.getAccountId(), intent);
                        return true;
                    }
                    return false;
                }
            } else if (queue.contains(intent.getAccountId())) {
                current = DispatchIntent.auto(intent.getAccountId(), intent.getEnqueuedAt());
                queuedIntents.put(intent.getAccountId(), current);
                if (current.getPriority() >= intent.getPriority()) {
                    return false;
                }
            }

            queue.removeIf(intent.getAccountId()::equals);
            var insertAt = 0;
            while (insertAt < queue.size()
                    && priorityOf(queue.get(insertAt)) >= intent.getPriority()) {
                insertAt++;
            }
            queue.add(insertAt, intent.getAccountId());
            queuedIntents.put(intent.getAccountId(), intent);
            return true;
        }
    }

    private int priorityOf(Long accountId) {
        var intent = queuedIntents.get(accountId);
        return intent == null ? DispatchIntent.PRIORITY_AUTO : intent.getPriority();
    }

    private boolean isEligible(Long accountId, LocalDateTime now) {
        if (accountId == null) {
            return false;
        }
        AccountEntity account = accountMapper.selectById(accountId);
        return account != null
                && !Integer.valueOf(1).equals(account.getDelete())
                && !Integer.valueOf(1).equals(account.getFreeze())
                && !dynamicInfo.getWorkUserList().contains(accountId)
                && (account.getExpireTime() == null || !account.getExpireTime().isBefore(now));
    }

    private boolean isDispatchable(AccountScheduledRunEntity run, LocalDateTime now) {
        if (run == null) {
            return false;
        }
        if (AccountScheduledRunService.STATUS_WAITING.equals(run.getStatus())) {
            return true;
        }
        return AccountScheduledRunService.STATUS_RETRY_WAIT.equals(run.getStatus())
                && (run.getNextRetryAt() == null || !run.getNextRetryAt().isAfter(now));
    }

    private boolean isDispatchable(UrgentTaskEntity task, LocalDateTime now) {
        if (task == null) {
            return false;
        }
        var waiting = UrgentTaskService.STATUS_WAITING.equals(task.getStatus());
        var retryable = UrgentTaskService.STATUS_RETRY_WAIT.equals(task.getStatus())
                || UrgentTaskService.STATUS_FAILED.equals(task.getStatus());
        return waiting || retryable
                && (task.getNextRetryAt() == null || !task.getNextRetryAt().isAfter(now));
    }

    private LocalDateTime firstNonNull(LocalDateTime value, LocalDateTime fallback) {
        return value == null ? fallback : value;
    }
}
