package moe.dazecake.inquisition.service.impl;

import moe.dazecake.inquisition.mapper.AccountDispatchConfigMapper;
import moe.dazecake.inquisition.mapper.AccountMapper;
import moe.dazecake.inquisition.model.entity.AccountDispatchConfigEntity;
import moe.dazecake.inquisition.model.entity.AccountEntity;
import moe.dazecake.inquisition.model.entity.AccountScheduledRunEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
public class AccountScheduledDispatchService {

    @Resource
    AccountDispatchConfigMapper configMapper;

    @Resource
    AccountMapper accountMapper;

    @Resource
    AccountScheduledRunService runService;

    @Resource
    AccountScheduleCalculator calculator;

    @Transactional
    public List<AccountScheduledRunEntity> scan(LocalDateTime now) {
        Objects.requireNonNull(now, "now");
        var dueConfigurations = configMapper.selectDue(now);
        if (dueConfigurations != null) {
            for (var candidate : dueConfigurations) {
                if (candidate != null && candidate.getAccountId() != null) {
                    processDue(candidate.getAccountId(), now);
                }
            }
        }
        return restoreDispatchable(now);
    }

    public List<AccountScheduledRunEntity> restoreDispatchable(LocalDateTime now) {
        Objects.requireNonNull(now, "now");
        var runs = runService.findDispatchable(now);
        return runs == null ? new ArrayList<>() : runs;
    }

    private void processDue(Long accountId, LocalDateTime now) {
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
            clearDue(config, dueAt);
            return;
        }
        if (!calculator.belongsToCurrentGameDay(dueAt, now)) {
            advanceDue(config, account, dueAt, now);
            return;
        }

        runService.createWaiting(accountId, dueAt);
        clearDue(config, dueAt);
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
                            LocalDateTime dueAt, LocalDateTime now) {
        if (config.getScheduleTime() == null) {
            throw new IllegalStateException("Persisted SCHEDULED configuration has no scheduleTime");
        }
        var next = calculator.nextOccurrence(account, config.getScheduleTime(), now);
        if (next == null || !next.isAfter(now)) {
            throw new IllegalStateException("Next account schedule must be strictly in the future");
        }
        if (configMapper.advanceDue(config.getAccountId(), dueAt, next) != 1) {
            throw new IllegalStateException("Unable to advance due account schedule");
        }
    }
}
