package moe.dazecake.inquisition.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import moe.dazecake.inquisition.mapper.AccountDispatchConfigMapper;
import moe.dazecake.inquisition.mapper.AccountScheduledRunMapper;
import moe.dazecake.inquisition.model.entity.AccountScheduledRunEntity;
import moe.dazecake.inquisition.utils.GameDayClock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

@Service
public class AccountScheduledRunService {
    public static final String STATUS_WAITING = "WAITING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_RETRY_WAIT = "RETRY_WAIT";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_FAILED = "FAILED";

    private static final Set<String> ACTIVE_STATUSES = Set.of(
            STATUS_WAITING, STATUS_RUNNING, STATUS_RETRY_WAIT);

    @Value("${inquisition.accountSchedule.scanBatchSize:200}")
    int batchSize = AccountScheduledDispatchService.DEFAULT_BATCH_SIZE;

    @Resource
    AccountScheduledRunMapper runMapper;

    @Resource
    AccountDispatchConfigMapper configMapper;

    @Transactional
    public AccountScheduledRunEntity createWaiting(Long accountId, LocalDateTime scheduledFor) {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(scheduledFor, "scheduledFor");

        if (configMapper.selectByIdForUpdate(accountId) == null) {
            throw new IllegalStateException("Account dispatch configuration does not exist");
        }
        var existingOccurrence = findByOccurrenceForUpdate(accountId, scheduledFor);
        if (existingOccurrence.isPresent()) {
            return existingOccurrence.get();
        }
        var active = findActiveByAccountForUpdate(accountId);
        if (active.isPresent()) {
            return active.get();
        }

        var created = new AccountScheduledRunEntity()
                .setAccountId(accountId)
                .setScheduledFor(scheduledFor)
                .setGameDay(GameDayClock.gameDay(scheduledFor))
                .setStatus(STATUS_WAITING)
                .setAttemptCount(0);
        try {
            if (runMapper.insert(created) != 1) {
                throw new IllegalStateException("Unable to persist scheduled run");
            }
        } catch (DuplicateKeyException exception) {
            return findByOccurrenceForUpdate(accountId, scheduledFor)
                    .or(() -> findActiveByAccountForUpdate(accountId))
                    .orElseThrow(() -> exception);
        }
        return created;
    }

    public Optional<AccountScheduledRunEntity> findActiveByAccount(Long accountId) {
        if (accountId == null) {
            return Optional.empty();
        }
        return findActiveByAccount(accountId, false);
    }

    private Optional<AccountScheduledRunEntity> findActiveByAccountForUpdate(Long accountId) {
        return findActiveByAccount(accountId, true);
    }

    private Optional<AccountScheduledRunEntity> findActiveByAccount(Long accountId, boolean forUpdate) {
        var query = Wrappers.<AccountScheduledRunEntity>lambdaQuery()
                .eq(AccountScheduledRunEntity::getAccountId, accountId)
                .in(AccountScheduledRunEntity::getStatus, ACTIVE_STATUSES)
                .orderByAsc(AccountScheduledRunEntity::getScheduledFor);
        query.last(forUpdate ? "LIMIT 1 FOR UPDATE" : "LIMIT 1");
        var run = runMapper.selectOne(query);
        return run != null && isActiveStatus(run.getStatus())
                ? Optional.of(run)
                : Optional.empty();
    }

    public Optional<AccountScheduledRunEntity> findById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(runMapper.selectById(id));
    }

    public List<AccountScheduledRunEntity> findDispatchable(LocalDateTime now) {
        Objects.requireNonNull(now, "now");
        if (batchSize <= 0) {
            throw new IllegalStateException("Scheduled account dispatch batch size must be positive");
        }
        var rows = runMapper.selectDispatchable(now, batchSize);
        if (rows == null) {
            return new ArrayList<>();
        }
        return rows;
    }

    @Transactional
    public boolean markRunning(Long id) {
        return transition(id, Set.of(STATUS_WAITING), update -> update
                        .set("status", STATUS_RUNNING)
                        .set("next_retry_at", null)
                        .set("last_error", null)
                        .setSql("attempt_count = attempt_count + 1"),
                run -> run.setStatus(STATUS_RUNNING)
                        .setAttemptCount(valueOrZero(run.getAttemptCount()) + 1)
                        .setNextRetryAt(null)
                        .setLastError(null));
    }

    @Transactional
    public boolean markRetry(Long id, String error, LocalDateTime nextRetryAt) {
        Objects.requireNonNull(nextRetryAt, "nextRetryAt");
        var boundedError = trim(error);
        return transition(id, Set.of(STATUS_WAITING, STATUS_RUNNING), update -> update
                        .set("status", STATUS_RETRY_WAIT)
                        .set("next_retry_at", nextRetryAt)
                        .set("last_error", boundedError),
                run -> run.setStatus(STATUS_RETRY_WAIT)
                        .setNextRetryAt(nextRetryAt)
                        .setLastError(boundedError));
    }

    @Transactional
    public boolean markWaiting(Long id) {
        var run = findById(id).orElse(null);
        if (run == null || !isActiveStatus(run.getStatus())) {
            return false;
        }
        if (STATUS_WAITING.equals(run.getStatus())) {
            return true;
        }
        return transition(run, Set.of(STATUS_RETRY_WAIT), update -> update
                        .set("status", STATUS_WAITING)
                        .set("next_retry_at", null)
                        .set("last_error", null),
                current -> current.setStatus(STATUS_WAITING)
                        .setNextRetryAt(null)
                        .setLastError(null));
    }

    @Transactional
    public boolean retryNow(Long id) {
        return transition(id, Set.of(STATUS_RETRY_WAIT), update -> update
                        .set("status", STATUS_WAITING)
                        .set("next_retry_at", null)
                        .set("last_error", null),
                run -> run.setStatus(STATUS_WAITING)
                        .setNextRetryAt(null)
                        .setLastError(null));
    }

    @Transactional
    public boolean succeed(Long id) {
        return terminalTransition(id, Set.of(STATUS_RUNNING), STATUS_SUCCEEDED, null);
    }

    @Transactional
    public boolean cancel(Long id) {
        return terminalTransition(id, ACTIVE_STATUSES, STATUS_CANCELLED, null);
    }

    @Transactional
    public boolean fail(Long id, String error) {
        return terminalTransition(id, ACTIVE_STATUSES, STATUS_FAILED, trim(error));
    }

    public static boolean isActiveStatus(String status) {
        return ACTIVE_STATUSES.contains(status);
    }

    private Optional<AccountScheduledRunEntity> findByOccurrence(Long accountId,
                                                                 LocalDateTime scheduledFor) {
        return findByOccurrence(accountId, scheduledFor, false);
    }

    private Optional<AccountScheduledRunEntity> findByOccurrenceForUpdate(
            Long accountId, LocalDateTime scheduledFor) {
        return findByOccurrence(accountId, scheduledFor, true);
    }

    private Optional<AccountScheduledRunEntity> findByOccurrence(
            Long accountId, LocalDateTime scheduledFor, boolean forUpdate) {
        var query = Wrappers.<AccountScheduledRunEntity>lambdaQuery()
                .eq(AccountScheduledRunEntity::getAccountId, accountId)
                .eq(AccountScheduledRunEntity::getScheduledFor, scheduledFor);
        if (forUpdate) {
            query.last("FOR UPDATE");
        }
        return Optional.ofNullable(runMapper.selectOne(query));
    }

    private boolean terminalTransition(Long id, Set<String> allowedStatuses,
                                       String terminalStatus, String error) {
        return transition(id, allowedStatuses, update -> update
                        .set("status", terminalStatus)
                        .set("next_retry_at", null)
                        .set("last_error", error)
                        .setSql("finished_at = CURRENT_TIMESTAMP(6)"),
                run -> run.setStatus(terminalStatus)
                        .setNextRetryAt(null)
                        .setLastError(error));
    }

    private boolean transition(Long id, Set<String> allowedStatuses,
                               Consumer<UpdateWrapper<AccountScheduledRunEntity>> updateSql,
                               Consumer<AccountScheduledRunEntity> localUpdate) {
        return transition(findById(id).orElse(null), allowedStatuses, updateSql, localUpdate);
    }

    private boolean transition(AccountScheduledRunEntity run, Set<String> allowedStatuses,
                               Consumer<UpdateWrapper<AccountScheduledRunEntity>> updateSql,
                               Consumer<AccountScheduledRunEntity> localUpdate) {
        if (run == null || !allowedStatuses.contains(run.getStatus())) {
            return false;
        }
        var update = Wrappers.<AccountScheduledRunEntity>update()
                .eq("id", run.getId())
                .eq("status", run.getStatus());
        updateSql.accept(update);
        if (runMapper.update(null, update) != 1) {
            throw new IllegalStateException("Unable to update scheduled run");
        }
        localUpdate.accept(run);
        return true;
    }

    private int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    private String trim(String value) {
        if (value == null || value.length() <= 255) {
            return value;
        }
        return value.substring(0, 255);
    }
}
