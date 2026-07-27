package moe.dazecake.inquisition.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import moe.dazecake.inquisition.mapper.AccountRuntimeMapper;
import moe.dazecake.inquisition.mapper.SklandCredentialMapper;
import moe.dazecake.inquisition.model.entity.AccountEntity;
import moe.dazecake.inquisition.model.entity.AccountRuntimeEntity;
import moe.dazecake.inquisition.model.entity.SklandCredentialEntity;
import moe.dazecake.inquisition.utils.DynamicInfo;
import moe.dazecake.inquisition.utils.GameDayClock;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

@Slf4j
@Service
public class SklandCalibrationService {
    public static final Duration MIN_QUERY_INTERVAL = Duration.ofHours(1);
    private final Semaphore querySlots = new Semaphore(2);
    private final ConcurrentHashMap<Long, Object> accountLocks = new ConcurrentHashMap<>();

    @Resource
    SklandCredentialService credentialService;

    @Resource
    AccountRuntimeMapper runtimeMapper;

    @Resource
    DynamicInfo dynamicInfo;

    @Resource
    SklandClient sklandClient;

    public Optional<SklandCalibrationResult> calibrate(AccountEntity account) {
        return calibrate(account, GameDayClock.now());
    }

    public Optional<SklandCalibrationResult> calibrate(AccountEntity account, LocalDateTime now) {
        if (account == null || account.getId() == null) {
            return Optional.empty();
        }
        var lock = accountLocks.computeIfAbsent(account.getId(), key -> new Object());
        synchronized (lock) {
            return calibrateLocked(account, now);
        }
    }

    private Optional<SklandCalibrationResult> calibrateLocked(AccountEntity account, LocalDateTime now) {
        var runtime = runtimeMapper.selectById(account.getId());
        if (runtime != null && runtime.getLastSklandQueryAt() != null
                && runtime.getLastSklandQueryAt().plus(MIN_QUERY_INTERVAL).isAfter(now)
                && runtime.getSanity() != null && runtime.getMaxSanity() != null) {
            return Optional.of(new SklandCalibrationResult(runtime.getSanity(), runtime.getMaxSanity(),
                    runtime.getLastOnlineAt(), runtime.getSanityObservedAt()));
        }

        var credential = credentialService.ensureCredential(account.getId()).orElse(null);
        if (!hasQueryCredential(credential) || !querySlots.tryAcquire()) {
            return Optional.empty();
        }
        try {
            SklandPlayerStatus status;
            try {
                status = sklandClient.queryPlayerInfo(credential);
            } catch (Exception firstFailure) {
                if (!isCredentialError(firstFailure) || credential.getCred() == null
                        || credential.getCred().isBlank()) {
                    throw firstFailure;
                }
                credential.setCredToken(sklandClient.refreshCredToken(credential.getCred()))
                        .setLastRefreshAt(now)
                        .setLastError(null)
                        .setUpdatedAt(now);
                credentialService.save(credential);
                status = sklandClient.queryPlayerInfo(credential);
            }
            // Persist the exact value returned by Skland without local recovery projection.
            var sanity = status.getCurrentSanity();
            var result = new SklandCalibrationResult(sanity, status.getMaxSanity(),
                    status.getLastOnlineAt(), now);
            if (runtime == null) {
                runtime = new AccountRuntimeEntity().setAccountId(account.getId());
            }
            runtime.setLastSklandQueryAt(now)
                    .setLastOnlineAt(status.getLastOnlineAt())
                    .setSanity(sanity)
                    .setMaxSanity(status.getMaxSanity())
                    .setSanityObservedAt(now)
                    .setSanitySource("SKLAND")
                    .setLastError(null)
                    .setUpdatedAt(now);
            saveRuntime(runtime);
            dynamicInfo.setUserSan(account.getId(), sanity, status.getMaxSanity());
            return Optional.of(result);
        } catch (Exception exception) {
            log.warn("森空岛校准失败，账号 {} 将降级使用本地状态", account.getId(), exception);
            if (runtime == null) {
                runtime = new AccountRuntimeEntity().setAccountId(account.getId());
            }
            runtime.setLastSklandQueryAt(now)
                    .setLastError(trimError(exception))
                    .setUpdatedAt(now);
            saveRuntime(runtime);
            return Optional.empty();
        } finally {
            querySlots.release();
        }
    }

    private boolean hasQueryCredential(SklandCredentialEntity credential) {
        return credential != null && nonBlank(credential.getCred())
                && nonBlank(credential.getCredToken()) && nonBlank(credential.getUid());
    }

    private boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private boolean isCredentialError(Exception exception) {
        var message = exception.getMessage();
        return message != null && (message.contains("code=10000") || message.contains("code=10002"));
    }

    private String trimError(Exception exception) {
        var message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() > 240 ? message.substring(0, 240) : message;
    }

    private void saveRuntime(AccountRuntimeEntity runtime) {
        if (runtimeMapper.selectById(runtime.getAccountId()) == null) {
            runtimeMapper.insert(runtime);
        } else {
            runtimeMapper.updateById(runtime);
        }
    }
}
