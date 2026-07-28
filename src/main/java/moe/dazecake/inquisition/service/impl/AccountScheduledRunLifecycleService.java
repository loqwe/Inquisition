package moe.dazecake.inquisition.service.impl;

import moe.dazecake.inquisition.mapper.AccountDispatchConfigMapper;
import moe.dazecake.inquisition.mapper.AccountMapper;
import moe.dazecake.inquisition.model.entity.AccountDispatchConfigEntity;
import moe.dazecake.inquisition.model.entity.AccountEntity;
import moe.dazecake.inquisition.model.entity.TaskAssignmentEntity;
import moe.dazecake.inquisition.model.local.DispatchIntent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Objects;

@Service
public class AccountScheduledRunLifecycleService {

    @Resource
    AccountScheduledRunService runService;

    @Resource
    AccountDispatchConfigMapper configMapper;

    @Resource
    AccountDispatchConfigService configService;

    @Resource
    AccountMapper accountMapper;

    @Resource
    AccountScheduleCalculator calculator;

    @Transactional
    public void start(DispatchIntent intent) {
        if (intent == null || !DispatchIntent.SOURCE_SCHEDULED.equals(intent.getSource())) {
            return;
        }
        if (intent.getScheduledRunId() == null || !runService.markRunning(intent.getScheduledRunId())) {
            throw new IllegalStateException("Unable to start scheduled run");
        }
    }

    @Transactional
    public boolean complete(TaskAssignmentEntity assignment, LocalDateTime completedAt) {
        if (!isScheduled(assignment)) {
            return true;
        }
        Objects.requireNonNull(completedAt, "completedAt");
        var config = lockedConfig(assignment.getAccountId());
        if (!runService.succeed(assignment.getScheduledRunId())) {
            throw new IllegalStateException("Unable to complete scheduled run");
        }
        advanceOrActivate(config, completedAt);
        return true;
    }

    @Transactional
    public boolean retry(TaskAssignmentEntity assignment, String error, LocalDateTime retryAt) {
        if (!isScheduled(assignment)) {
            return true;
        }
        Objects.requireNonNull(retryAt, "retryAt");
        var config = lockedConfig(assignment.getAccountId());
        if (Integer.valueOf(1).equals(config.getActivationPending())) {
            if (!runService.cancel(assignment.getScheduledRunId())) {
                throw new IllegalStateException("Unable to cancel scheduled run for pending mode change");
            }
            activatePending(assignment.getAccountId(), retryAt);
            return false;
        }
        if (!runService.markRetry(assignment.getScheduledRunId(), error, retryAt)) {
            throw new IllegalStateException("Unable to retry scheduled run");
        }
        return true;
    }

    @Transactional
    public void cancel(TaskAssignmentEntity assignment, LocalDateTime cancelledAt) {
        if (!isScheduled(assignment)) {
            return;
        }
        Objects.requireNonNull(cancelledAt, "cancelledAt");
        var config = lockedConfig(assignment.getAccountId());
        if (!runService.cancel(assignment.getScheduledRunId())) {
            throw new IllegalStateException("Unable to cancel scheduled run");
        }
        advanceOrActivate(config, cancelledAt);
    }

    @Transactional
    public void fail(TaskAssignmentEntity assignment, String error) {
        if (isScheduled(assignment)
                && !runService.fail(assignment.getScheduledRunId(), error)) {
            throw new IllegalStateException("Unable to fail scheduled run");
        }
    }

    @Transactional
    public boolean activatePendingIfReady(TaskAssignmentEntity assignment,
                                          LocalDateTime closedAt) {
        if (assignment == null || assignment.getAccountId() == null) {
            return false;
        }
        Objects.requireNonNull(closedAt, "closedAt");
        var config = configMapper.selectByIdForUpdate(assignment.getAccountId());
        if (config == null || !Integer.valueOf(1).equals(config.getActivationPending())) {
            return false;
        }
        if (runService.findActiveByAccount(assignment.getAccountId()).isPresent()) {
            return false;
        }
        activatePending(assignment.getAccountId(), closedAt);
        return true;
    }

    private boolean isScheduled(TaskAssignmentEntity assignment) {
        return assignment != null
                && DispatchIntent.SOURCE_SCHEDULED.equals(assignment.getDispatchSource())
                && assignment.getScheduledRunId() != null;
    }

    private AccountDispatchConfigEntity lockedConfig(Long accountId) {
        var config = configMapper.selectByIdForUpdate(accountId);
        if (config == null) {
            throw new IllegalStateException("Scheduled account dispatch configuration does not exist");
        }
        return config;
    }

    private void advanceOrActivate(AccountDispatchConfigEntity config, LocalDateTime now) {
        if (Integer.valueOf(1).equals(config.getActivationPending())) {
            activatePending(config.getAccountId(), now);
            return;
        }
        if (!AccountDispatchConfigService.SCHEDULED.equals(config.getDispatchMode())) {
            return;
        }
        if (config.getScheduleTime() == null) {
            throw new IllegalStateException("Persisted SCHEDULED configuration has no scheduleTime");
        }
        var account = accountMapper.selectById(config.getAccountId());
        if (!isSchedulable(account, now)) {
            return;
        }
        var next = calculator.nextOccurrence(account, config.getScheduleTime(), now);
        if (next == null || !next.isAfter(now)
                || configMapper.scheduleNext(config.getAccountId(), next) != 1) {
            throw new IllegalStateException("Unable to schedule the next account run");
        }
    }

    private void activatePending(Long accountId, LocalDateTime now) {
        var account = accountMapper.selectById(accountId);
        if (account == null) {
            throw new IllegalStateException("Unable to activate dispatch configuration for a missing account");
        }
        configService.activatePending(account, now);
    }

    private boolean isSchedulable(AccountEntity account, LocalDateTime now) {
        return account != null
                && "daily".equals(account.getTaskType())
                && Integer.valueOf(0).equals(account.getDelete())
                && Integer.valueOf(0).equals(account.getFreeze())
                && account.getExpireTime() != null
                && account.getExpireTime().isAfter(now);
    }
}
