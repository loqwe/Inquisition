package moe.dazecake.inquisition.service.impl;

import moe.dazecake.inquisition.mapper.AccountDispatchConfigMapper;
import moe.dazecake.inquisition.model.dto.account.AccountDispatchConfigDTO;
import moe.dazecake.inquisition.model.entity.AccountDispatchConfigEntity;
import moe.dazecake.inquisition.model.entity.AccountEntity;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Objects;

@Service
public class AccountDispatchConfigService {
    public static final String AUTO = "AUTO";
    public static final String SCHEDULED = "SCHEDULED";

    @Resource
    AccountDispatchConfigMapper configMapper;

    @Resource
    AccountScheduleCalculator calculator;

    public AccountDispatchConfigEntity getOrDefault(Long accountId) {
        Objects.requireNonNull(accountId, "accountId");
        var config = configMapper.selectById(accountId);
        if (config != null) {
            return config;
        }
        return new AccountDispatchConfigEntity()
                .setAccountId(accountId)
                .setDispatchMode(AUTO)
                .setActivationPending(0);
    }

    public boolean isAuto(Long accountId) {
        return AUTO.equals(getOrDefault(accountId).getDispatchMode());
    }

    public void update(AccountEntity account, AccountDispatchConfigDTO request,
                       boolean assignmentActive, LocalDateTime now) {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(account.getId(), "account.id");
        Objects.requireNonNull(now, "now");
        var mode = validatedMode(request);

        LocalDateTime nextScheduledAt = null;
        if (SCHEDULED.equals(mode)) {
            if (request.getScheduleTime() == null) {
                throw new IllegalArgumentException("scheduleTime is required for SCHEDULED mode");
            }
            nextScheduledAt = calculator.nextOccurrence(account, request.getScheduleTime(), now);
        }

        var config = configMapper.selectById(account.getId());
        var exists = config != null;
        if (!exists) {
            config = new AccountDispatchConfigEntity().setAccountId(account.getId());
        }
        config.setDispatchMode(mode)
                .setScheduleTime(SCHEDULED.equals(mode) ? request.getScheduleTime() : null)
                .setNextScheduledAt(assignmentActive ? null : nextScheduledAt)
                .setActivationPending(assignmentActive ? 1 : 0);
        save(config, exists);
    }

    public void activatePending(AccountEntity account, LocalDateTime now) {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(account.getId(), "account.id");
        Objects.requireNonNull(now, "now");
        var config = configMapper.selectById(account.getId());
        if (config == null || !Integer.valueOf(1).equals(config.getActivationPending())) {
            return;
        }

        var mode = validatedMode(config.getDispatchMode());
        LocalDateTime nextScheduledAt = null;
        if (SCHEDULED.equals(mode)) {
            if (config.getScheduleTime() == null) {
                throw new IllegalArgumentException("scheduleTime is required for SCHEDULED mode");
            }
            nextScheduledAt = calculator.nextOccurrence(account, config.getScheduleTime(), now);
        } else {
            config.setScheduleTime(null);
        }
        config.setNextScheduledAt(nextScheduledAt)
                .setActivationPending(0);
        configMapper.updateById(config);
    }

    private String validatedMode(AccountDispatchConfigDTO request) {
        if (request == null) {
            throw new IllegalArgumentException("dispatch configuration is required");
        }
        return validatedMode(request.getDispatchMode());
    }

    private String validatedMode(String mode) {
        if (!AUTO.equals(mode) && !SCHEDULED.equals(mode)) {
            throw new IllegalArgumentException("dispatchMode must be AUTO or SCHEDULED");
        }
        return mode;
    }

    private void save(AccountDispatchConfigEntity config, boolean exists) {
        if (exists) {
            configMapper.updateById(config);
        } else {
            configMapper.insert(config);
        }
    }
}
