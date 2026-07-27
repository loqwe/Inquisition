package moe.dazecake.inquisition.service.impl;

import moe.dazecake.inquisition.mapper.AccountDispatchConfigMapper;
import moe.dazecake.inquisition.model.dto.account.AccountDispatchConfigDTO;
import moe.dazecake.inquisition.model.entity.AccountDispatchConfigEntity;
import moe.dazecake.inquisition.model.entity.AccountEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.LocalTime;
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

    @Transactional
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

        var config = new AccountDispatchConfigEntity()
                .setAccountId(account.getId())
                .setDispatchMode(mode)
                .setScheduleTime(SCHEDULED.equals(mode) ? request.getScheduleTime() : null)
                .setNextScheduledAt(assignmentActive ? null : nextScheduledAt)
                .setActivationPending(assignmentActive ? 1 : 0);
        var affectedRows = configMapper.upsert(config);
        if (affectedRows < 0
                || affectedRows == 0 && !sameConfiguration(config, configMapper.selectById(account.getId()))) {
            throw new IllegalStateException("Unable to persist account dispatch configuration");
        }
    }

    @Transactional
    public void activatePending(AccountEntity account, LocalDateTime now) {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(account.getId(), "account.id");
        Objects.requireNonNull(now, "now");
        var config = configMapper.selectByIdForUpdate(account.getId());
        if (config == null || !Integer.valueOf(1).equals(config.getActivationPending())) {
            return;
        }

        var mode = validatedPersistedMode(config.getDispatchMode());
        LocalTime scheduleTime = null;
        LocalDateTime nextScheduledAt = null;
        if (SCHEDULED.equals(mode)) {
            if (config.getScheduleTime() == null) {
                throw new IllegalStateException("Persisted SCHEDULED configuration has no scheduleTime");
            }
            scheduleTime = config.getScheduleTime();
            try {
                nextScheduledAt = calculator.nextOccurrence(account, scheduleTime, now);
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("Persisted dispatch configuration is invalid", exception);
            }
        }
        if (configMapper.completePendingActivation(account.getId(), scheduleTime, nextScheduledAt) != 1) {
            throw new IllegalStateException("Unable to activate pending account dispatch configuration");
        }
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

    private String validatedPersistedMode(String mode) {
        try {
            return validatedMode(mode);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Persisted dispatch configuration is invalid", exception);
        }
    }

    private boolean sameConfiguration(AccountDispatchConfigEntity expected,
                                      AccountDispatchConfigEntity persisted) {
        return persisted != null
                && Objects.equals(expected.getDispatchMode(), persisted.getDispatchMode())
                && Objects.equals(expected.getScheduleTime(), persisted.getScheduleTime())
                && Objects.equals(expected.getNextScheduledAt(), persisted.getNextScheduledAt())
                && Objects.equals(expected.getActivationPending(), persisted.getActivationPending());
    }
}
