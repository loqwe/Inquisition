package moe.dazecake.inquisition.service.impl;

import moe.dazecake.inquisition.mapper.AccountDispatchConfigMapper;
import moe.dazecake.inquisition.mapper.AccountDispatchTimeMapper;
import moe.dazecake.inquisition.model.dto.account.AccountDispatchConfigDTO;
import moe.dazecake.inquisition.model.entity.AccountDispatchConfigEntity;
import moe.dazecake.inquisition.model.entity.AccountDispatchTimeEntity;
import moe.dazecake.inquisition.model.entity.AccountEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

@Service
public class AccountDispatchConfigService {
    public static final String AUTO = "AUTO";
    public static final String SCHEDULED = "SCHEDULED";
    public static final int MAX_SCHEDULE_TIMES = 3;

    @Resource
    AccountDispatchConfigMapper configMapper;

    @Resource
    AccountDispatchTimeMapper timeMapper;

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
        var scheduleTimes = validatedTimes(request, mode);

        LocalDateTime nextScheduledAt = null;
        if (SCHEDULED.equals(mode)) {
            nextScheduledAt = calculator.nextOccurrence(account, scheduleTimes, now);
        }

        var config = new AccountDispatchConfigEntity()
                .setAccountId(account.getId())
                .setDispatchMode(mode)
                .setScheduleTime(firstOrNull(scheduleTimes))
                .setNextScheduledAt(assignmentActive ? null : nextScheduledAt)
                .setActivationPending(assignmentActive ? 1 : 0);
        var affectedRows = configMapper.upsert(config);
        if (affectedRows < 0
                || affectedRows == 0 && !sameConfiguration(config, configMapper.selectById(account.getId()))) {
            throw new IllegalStateException("Unable to persist account dispatch configuration");
        }
        replaceTimes(account.getId(), scheduleTimes);
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
            var scheduleTimes = persistedTimes(config);
            if (scheduleTimes.isEmpty()) {
                throw new IllegalStateException("Persisted SCHEDULED configuration has no scheduleTime");
            }
            scheduleTime = scheduleTimes.get(0);
            try {
                nextScheduledAt = calculator.nextOccurrence(account, scheduleTimes, now);
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

    public List<LocalTime> getScheduleTimes(AccountDispatchConfigEntity config) {
        if (config == null || config.getAccountId() == null
                || !SCHEDULED.equals(config.getDispatchMode())) {
            return List.of();
        }
        return persistedTimes(config);
    }

    private List<LocalTime> validatedTimes(AccountDispatchConfigDTO request, String mode) {
        if (!SCHEDULED.equals(mode)) {
            return List.of();
        }
        var requested = request.getScheduleTimes() == null
                ? new ArrayList<LocalTime>()
                : new ArrayList<>(request.getScheduleTimes());
        if (requested.isEmpty() && request.getScheduleTime() != null) {
            requested.add(request.getScheduleTime());
        }
        if (requested.isEmpty()) {
            throw new IllegalArgumentException("scheduleTimes is required for SCHEDULED mode");
        }
        if (requested.size() > MAX_SCHEDULE_TIMES) {
            throw new IllegalArgumentException("scheduleTimes supports at most 3 values");
        }
        if (requested.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("scheduleTimes cannot contain null");
        }
        var unique = new TreeSet<>(requested);
        if (unique.size() != requested.size()) {
            throw new IllegalArgumentException("scheduleTimes cannot contain duplicates");
        }
        var sorted = new ArrayList<>(unique);
        if (request.getScheduleTime() != null
                && !Objects.equals(request.getScheduleTime(), sorted.get(0))) {
            throw new IllegalArgumentException("scheduleTime conflicts with scheduleTimes");
        }
        return sorted;
    }

    private List<LocalTime> persistedTimes(AccountDispatchConfigEntity config) {
        var persisted = timeMapper.selectTimes(config.getAccountId());
        if (persisted != null && !persisted.isEmpty()) {
            return normalizedPersistedTimes(persisted);
        }
        return config.getScheduleTime() == null
                ? List.of()
                : List.of(config.getScheduleTime());
    }

    private List<LocalTime> normalizedPersistedTimes(Collection<LocalTime> times) {
        if (times == null || times.isEmpty() || times.stream().anyMatch(Objects::isNull)) {
            throw new IllegalStateException("Persisted dispatch configuration is invalid");
        }
        var unique = new TreeSet<>(times);
        if (unique.size() != times.size() || unique.size() > MAX_SCHEDULE_TIMES) {
            throw new IllegalStateException("Persisted dispatch configuration is invalid");
        }
        return new ArrayList<>(unique);
    }

    private void replaceTimes(Long accountId, List<LocalTime> scheduleTimes) {
        timeMapper.deleteByAccountId(accountId);
        for (var scheduleTime : scheduleTimes) {
            var row = new AccountDispatchTimeEntity()
                    .setAccountId(accountId)
                    .setScheduleTime(scheduleTime);
            if (timeMapper.insert(row) != 1) {
                throw new IllegalStateException("Unable to persist account schedule time");
            }
        }
    }

    private LocalTime firstOrNull(List<LocalTime> scheduleTimes) {
        return scheduleTimes.isEmpty() ? null : scheduleTimes.get(0);
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
