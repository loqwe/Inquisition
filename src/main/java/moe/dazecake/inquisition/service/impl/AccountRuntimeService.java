package moe.dazecake.inquisition.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import moe.dazecake.inquisition.mapper.AccountMapper;
import moe.dazecake.inquisition.mapper.AccountRuntimeMapper;
import moe.dazecake.inquisition.mapper.LogMapper;
import moe.dazecake.inquisition.model.entity.AccountEntity;
import moe.dazecake.inquisition.model.entity.AccountRuntimeEntity;
import moe.dazecake.inquisition.model.entity.LogEntity;
import moe.dazecake.inquisition.utils.DynamicInfo;
import moe.dazecake.inquisition.utils.GameDayClock;
import moe.dazecake.inquisition.utils.GameLogClassifier;
import moe.dazecake.inquisition.utils.RetryBackoff;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
public class AccountRuntimeService {
    public static final long MISSING_LOG_HOURS = 9;

    @Resource
    AccountRuntimeMapper runtimeMapper;

    @Resource
    AccountMapper accountMapper;

    @Resource
    LogMapper logMapper;

    @Resource
    TaskAssignmentService taskAssignmentService;

    @Resource
    SklandCalibrationService sklandCalibrationService;

    @Resource
    DynamicInfo dynamicInfo;

    @Resource
    MessageServiceImpl messageService;

    public void enrichLogIdentity(LogEntity logEntity) {
        if (logEntity == null) {
            return;
        }
        if (logEntity.getFrom() != null && !logEntity.getFrom().isBlank()) {
            var assignment = taskAssignmentService.findByDevice(logEntity.getFrom()).orElse(null);
            var assignmentId = logEntity.getAssignmentId();
            if (assignment != null && (assignmentId == null || assignmentId.isBlank()
                    || Objects.equals(assignment.getAssignmentId(), assignmentId))) {
                logEntity.setAccountId(assignment.getAccountId());
                logEntity.setAssignmentId(assignment.getAssignmentId());
                return;
            }
        }
        var accountId = resolveAccountId(logEntity);
        if (accountId != null) {
            logEntity.setAccountId(accountId);
        }
    }

    public boolean onLog(LogEntity logEntity, boolean isSystem) {
        if (logEntity == null || isSystem) {
            return isSystem;
        }
        var accountId = resolveAccountId(logEntity);
        if (accountId == null) {
            return false;
        }

        var acceptedAssignment = true;
        if (logEntity.getFrom() != null) {
            acceptedAssignment = taskAssignmentService.recordProgress(
                    logEntity.getFrom(), logEntity.getAssignmentId(),
                    logEntity.getLevel(), logEntity.getTitle(), logEntity.getDetail());
        }
        if (!acceptedAssignment) {
            return false;
        }
        if (!GameLogClassifier.isValidGameLog(logEntity.getLevel(), logEntity.getTitle(), logEntity.getFrom())) {
            return true;
        }

        var now = logEntity.getTime() == null ? GameDayClock.now() : logEntity.getTime();
        var runtime = runtimeMapper.selectById(accountId);
        var exists = runtime != null;
        if (runtime == null) {
            runtime = new AccountRuntimeEntity().setAccountId(accountId);
        }
        runtime.setLastValidLogAt(now)
                .setGameDayKey(GameDayClock.gameDay(now))
                .setMissingLogNotified(0)
                .setAbnormal(0)
                .setLastError(null)
                .setUpdatedAt(now);
        if (GameLogClassifier.isLoginLog(logEntity.getTitle())) {
            runtime.setLastLoginAt(now);
        }
        saveRuntime(runtime, exists);
        return true;
    }

    public MissingLogResult checkMissingLogs(LocalDateTime now) {
        var gameDay = GameDayClock.gameDay(now);
        var missingAccounts = new ArrayList<AccountEntity>();
        var calibratedAccounts = new ArrayList<AccountEntity>();
        var accounts = accountMapper.selectList(Wrappers.<AccountEntity>lambdaQuery()
                .eq(AccountEntity::getDelete, 0)
                .eq(AccountEntity::getFreeze, 0)
                .ge(AccountEntity::getExpireTime, now));
        for (AccountEntity account : accounts) {
            var runtime = runtimeMapper.selectById(account.getId());
            var exists = runtime != null;
            if (runtime == null) {
                runtime = loadRuntimeFromLatestLog(account, now);
                exists = false;
            }
            if (!gameDay.equals(runtime.getGameDayKey())) {
                runtime.setGameDayKey(gameDay).setMissingLogNotified(0);
            }
            if (!GameDayClock.isMissingValidLog(now, runtime.getLastValidLogAt(), MISSING_LOG_HOURS)
                    || Integer.valueOf(1).equals(runtime.getMissingLogNotified())) {
                runtime.setUpdatedAt(now);
                saveRuntime(runtime, exists);
                continue;
            }

            var calibration = sklandCalibrationService.calibrate(account, now);
            // Skland calibration may create or update the runtime row. Reload it before
            // applying the missing-log result so fresh sanity fields are not overwritten
            // by the stale object loaded before the HTTP call.
            var refreshedRuntime = runtimeMapper.selectById(account.getId());
            if (refreshedRuntime != null) {
                runtime = refreshedRuntime;
                exists = true;
            }
            if (!gameDay.equals(runtime.getGameDayKey())) {
                runtime.setGameDayKey(gameDay).setMissingLogNotified(0);
            }
            if (calibration.isEmpty()) {
                runtime.setUpdatedAt(now);
                saveRuntime(runtime, exists);
                continue;
            }
            var result = calibration.get();
            if (result.getLastOnlineAt() != null
                    && !result.getLastOnlineAt().isBefore(GameDayClock.startOfGameDay(now))) {
                runtime.setLastValidLogAt(result.getLastOnlineAt())
                        .setLastLoginAt(result.getLastOnlineAt())
                        .setMissingLogNotified(0)
                        .setAbnormal(0)
                        .setLastError(null)
                        .setUpdatedAt(now);
                calibratedAccounts.add(account);
            } else {
                runtime.setMissingLogNotified(1)
                        .setAbnormal(1)
                        .setLastError("NO_VALID_GAME_LOG")
                        .setUpdatedAt(now);
                missingAccounts.add(account);
                messageService.push(account, "账号状态提醒",
                        "今天 04:00 后暂未检测到有效游戏日志，森空岛也未记录新的登录；请检查设备和账号状态。");
            }
            saveRuntime(runtime, exists);
        }

        if (!missingAccounts.isEmpty()) {
            var names = new ArrayList<String>();
            missingAccounts.forEach(account -> names.add(account.getName()));
            messageService.pushAdmin("[审判庭] 账号异常",
                    "满9小时未检测到有效游戏日志：" + String.join("、", names));
        }
        return new MissingLogResult(missingAccounts, calibratedAccounts);
    }

    public LocalDateTime recordFailure(AccountEntity account, String deviceToken, String reason,
                                       LocalDateTime now) {
        var runtime = runtimeMapper.selectById(account.getId());
        var exists = runtime != null;
        if (runtime == null) {
            runtime = new AccountRuntimeEntity().setAccountId(account.getId());
        }
        var retryCount = runtime.getRetryCount() == null ? 0 : runtime.getRetryCount();
        retryCount++;
        var nextEligibleAt = now.plusMinutes(RetryBackoff.delayMinutes(retryCount));
        runtime.setRetryCount(retryCount)
                .setLastFailureAt(now)
                .setLastFailureDeviceToken(deviceToken)
                .setNextEligibleAt(nextEligibleAt)
                .setLastError(reason)
                .setUpdatedAt(now);
        saveRuntime(runtime, exists);
        return nextEligibleAt;
    }

    public void recordTaskCompleted(Long accountId, LocalDateTime now) {
        if (accountId == null) {
            return;
        }
        var runtime = runtimeMapper.selectById(accountId);
        var exists = runtime != null;
        if (runtime == null) {
            runtime = new AccountRuntimeEntity().setAccountId(accountId);
        }
        runtime.setLastTaskCompletedAt(now)
                .setRetryCount(0)
                .setNextEligibleAt(null)
                .setLastFailureAt(null)
                .setLastFailureDeviceToken(null)
                .setLastError(null)
                .setUpdatedAt(now);
        saveRuntime(runtime, exists);
    }

    public void recordSklandSnapshot(Long accountId, int currentSanity, int maxSanity,
                                     long completeRecoveryTime, LocalDateTime lastOnlineAt,
                                     LocalDateTime observedAt) {
        if (accountId == null || observedAt == null || maxSanity <= 0) {
            return;
        }
        var runtime = runtimeMapper.selectById(accountId);
        var exists = runtime != null;
        if (runtime == null) {
            runtime = new AccountRuntimeEntity().setAccountId(accountId);
        }
        // Skland's current field is authoritative; recovery timestamps must not alter it.
        runtime.setLastSklandQueryAt(observedAt)
                .setLastOnlineAt(lastOnlineAt)
                .setSanity(currentSanity)
                .setMaxSanity(maxSanity)
                .setSanityObservedAt(observedAt)
                .setSanitySource("SKLAND_CALLBACK")
                .setLastError(null)
                .setUpdatedAt(observedAt);
        saveRuntime(runtime, exists);
        dynamicInfo.setUserSan(accountId, currentSanity, maxSanity);
    }

    public void recordOcrSnapshot(Long accountId, int currentSanity, int maxSanity,
                                  LocalDateTime observedAt) {
        if (accountId == null || observedAt == null || currentSanity < 0
                || maxSanity <= 0 || currentSanity > maxSanity || maxSanity > 999) {
            return;
        }
        var runtime = runtimeMapper.selectById(accountId);
        var exists = runtime != null;
        if (runtime == null) {
            runtime = new AccountRuntimeEntity().setAccountId(accountId);
        } else if (runtime.getSanityObservedAt() != null
                && !runtime.getSanityObservedAt().isBefore(observedAt)) {
            return;
        }
        var updatedAt = runtime.getUpdatedAt() != null && runtime.getUpdatedAt().isAfter(observedAt)
                ? runtime.getUpdatedAt() : observedAt;
        runtime.setSanity(currentSanity)
                .setMaxSanity(maxSanity)
                .setSanityObservedAt(observedAt)
                .setSanitySource("LOCAL_OCR")
                .setUpdatedAt(updatedAt);
        saveRuntime(runtime, exists);
        dynamicInfo.setUserSan(accountId, currentSanity, maxSanity);
    }

    public int restoreRetryCooldowns(LocalDateTime now) {
        var runtimes = runtimeMapper.selectList(Wrappers.<AccountRuntimeEntity>lambdaQuery()
                .gt(AccountRuntimeEntity::getNextEligibleAt, now));
        var restored = 0;
        for (AccountRuntimeEntity runtime : runtimes) {
            if (runtime.getAccountId() == null) {
                continue;
            }
            dynamicInfo.getFreezeUserInfoMap().put(runtime.getAccountId(), runtime.getNextEligibleAt());
            dynamicInfo.getCooldownReasonMap().put(runtime.getAccountId(), "retryBackoff");
            synchronized (dynamicInfo.getWaitUserList()) {
                if (!dynamicInfo.getWaitUserList().contains(runtime.getAccountId())) {
                    dynamicInfo.getWaitUserList().add(runtime.getAccountId());
                }
            }
            restored++;
        }
        return restored;
    }

    private AccountRuntimeEntity loadRuntimeFromLatestLog(AccountEntity account, LocalDateTime now) {
        var recentLogs = logMapper.selectList(Wrappers.<LogEntity>lambdaQuery()
                .and(wrapper -> wrapper.eq(LogEntity::getAccountId, account.getId())
                        .or()
                        .eq(LogEntity::getAccount, account.getAccount()))
                .eq(LogEntity::getDelete, 0)
                .orderByDesc(LogEntity::getTime)
                .last("LIMIT 20"));
        var runtime = new AccountRuntimeEntity().setAccountId(account.getId())
                .setGameDayKey(GameDayClock.gameDay(now));
        if (recentLogs != null) {
            for (LogEntity log : recentLogs) {
                if (!GameLogClassifier.isValidGameLog(log.getLevel(), log.getTitle(), log.getFrom())) {
                    continue;
                }
                runtime.setLastValidLogAt(log.getTime());
                if (GameLogClassifier.isLoginLog(log.getTitle())) {
                    runtime.setLastLoginAt(log.getTime());
                }
                break;
            }
        }
        return runtime;
    }

    private Long resolveAccountId(LogEntity logEntity) {
        if (logEntity.getAccountId() != null) {
            return logEntity.getAccountId();
        }
        if (logEntity.getAccount() == null || logEntity.getAccount().isBlank()) {
            return null;
        }
        var account = accountMapper.selectOne(Wrappers.<AccountEntity>lambdaQuery()
                .eq(AccountEntity::getAccount, logEntity.getAccount())
                .eq(AccountEntity::getDelete, 0)
                .orderByDesc(AccountEntity::getId)
                .last("LIMIT 1"));
        return account == null ? null : account.getId();
    }

    private void saveRuntime(AccountRuntimeEntity runtime, boolean exists) {
        if (exists) {
            runtimeMapper.updateById(runtime);
        } else {
            runtimeMapper.insert(runtime);
        }
    }

    public static final class MissingLogResult {
        private final List<AccountEntity> missingAccounts;
        private final List<AccountEntity> calibratedAccounts;

        public MissingLogResult(List<AccountEntity> missingAccounts, List<AccountEntity> calibratedAccounts) {
            this.missingAccounts = missingAccounts;
            this.calibratedAccounts = calibratedAccounts;
        }

        public List<AccountEntity> getMissingAccounts() {
            return missingAccounts;
        }

        public List<AccountEntity> getCalibratedAccounts() {
            return calibratedAccounts;
        }
    }
}
