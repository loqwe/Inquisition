package moe.dazecake.inquisition.service.impl;

import moe.dazecake.inquisition.mapper.AccountDispatchConfigMapper;
import moe.dazecake.inquisition.mapper.AccountMapper;
import moe.dazecake.inquisition.model.entity.AccountDispatchConfigEntity;
import moe.dazecake.inquisition.model.entity.AccountEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Objects;

@Service
public class AccountScheduledDispatchProcessor {

    @Resource
    AccountDispatchConfigMapper configMapper;

    @Resource
    AccountMapper accountMapper;

    @Resource
    AccountScheduledRunService runService;

    @Resource
    AccountDispatchConfigService configService;

    @Resource
    AccountScheduleCalculator calculator;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void process(Long accountId, LocalDateTime now) {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(now, "now");
        var config = configMapper.selectByIdForUpdate(accountId);
        if (!isStillDue(config, now) || Integer.valueOf(1).equals(config.getActivationPending())) {
            return;
        }

        var dueAt = config.getNextScheduledAt();
        var account = accountMapper.selectById(accountId);
        if (!isSchedulableDailyAccount(account, now)) {
            clearDue(config, dueAt);
            return;
        }
        if (Integer.valueOf(1).equals(account.getFreeze())) {
            advanceDue(config, account, dueAt, now);
            return;
        }

        var active = runService.findActiveByAccount(accountId);
        if (active.isPresent()) {
            return;
        }
        if (!calculator.belongsToCurrentGameDay(dueAt, now)) {
            advanceDue(config, account, dueAt, now);
            return;
        }

        runService.createWaiting(accountId, dueAt);
        advanceDue(config, account, dueAt, dueAt);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean repairMissingNext(Long accountId, LocalDateTime now) {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(now, "now");
        var config = configMapper.selectByIdForUpdate(accountId);
        if (!needsNextRepair(config)) {
            return false;
        }

        var account = accountMapper.selectById(accountId);
        if (!isSchedulableDailyAccount(account, now)) {
            return false;
        }
        var scheduleTimes = configService.getScheduleTimes(config);
        if (scheduleTimes.isEmpty()) {
            throw new IllegalStateException("Persisted SCHEDULED configuration has no scheduleTime");
        }
        var next = calculator.nextOccurrence(account, scheduleTimes, now);
        if (next == null || !next.isAfter(now)) {
            throw new IllegalStateException("Repaired account schedule must be in the future");
        }
        if (configMapper.scheduleNext(accountId, next) != 1) {
            throw new IllegalStateException("Unable to repair missing account schedule");
        }
        return true;
    }

    private boolean needsNextRepair(AccountDispatchConfigEntity config) {
        return config != null
                && AccountDispatchConfigService.SCHEDULED.equals(config.getDispatchMode())
                && Integer.valueOf(0).equals(config.getActivationPending())
                && config.getNextScheduledAt() == null;
    }

    private boolean isStillDue(AccountDispatchConfigEntity config, LocalDateTime now) {
        return config != null
                && AccountDispatchConfigService.SCHEDULED.equals(config.getDispatchMode())
                && config.getNextScheduledAt() != null
                && !config.getNextScheduledAt().isAfter(now);
    }

    private boolean isSchedulableDailyAccount(AccountEntity account, LocalDateTime now) {
        return account != null
                && "daily".equals(account.getTaskType())
                && Integer.valueOf(0).equals(account.getDelete())
                && account.getExpireTime() != null
                && account.getExpireTime().isAfter(now);
    }

    private void clearDue(AccountDispatchConfigEntity config, LocalDateTime dueAt) {
        if (configMapper.clearDue(config.getAccountId(), dueAt) != 1) {
            throw new IllegalStateException("Unable to clear due account schedule");
        }
    }

    private void advanceDue(AccountDispatchConfigEntity config, AccountEntity account,
                            LocalDateTime dueAt, LocalDateTime strictlyAfter) {
        var scheduleTimes = configService.getScheduleTimes(config);
        if (scheduleTimes.isEmpty()) {
            throw new IllegalStateException("Persisted SCHEDULED configuration has no scheduleTime");
        }
        var next = calculator.nextOccurrence(account, scheduleTimes, strictlyAfter);
        if (next == null || !next.isAfter(strictlyAfter)) {
            throw new IllegalStateException("Next account schedule must be strictly in the future");
        }
        if (configMapper.advanceDue(config.getAccountId(), dueAt, next) != 1) {
            throw new IllegalStateException("Unable to advance due account schedule");
        }
    }
}
