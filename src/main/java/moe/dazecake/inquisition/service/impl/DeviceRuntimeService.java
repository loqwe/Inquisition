package moe.dazecake.inquisition.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import moe.dazecake.inquisition.mapper.DeviceMapper;
import moe.dazecake.inquisition.mapper.DeviceRuntimeMapper;
import moe.dazecake.inquisition.model.entity.DeviceEntity;
import moe.dazecake.inquisition.model.entity.DeviceRuntimeEntity;
import moe.dazecake.inquisition.utils.DeviceNoticeSchedule;
import moe.dazecake.inquisition.utils.DynamicInfo;
import moe.dazecake.inquisition.utils.ImportantDevicePolicy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Set;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
public class DeviceRuntimeService {
    public static final Duration HEARTBEAT_GRACE = Duration.ofMinutes(30);
    public static final Duration SOFT_DELETE_AFTER = Duration.ofHours(24);

    @Resource
    DeviceRuntimeMapper runtimeMapper;

    @Resource
    DeviceMapper deviceMapper;

    @Resource
    DynamicInfo dynamicInfo;

    @Resource
    TaskRecoveryService taskRecoveryService;

    @Resource
    MessageServiceImpl messageService;

    // Skland calibration is bounded to two concurrent requests. Keep device recovery
    // off the scheduler thread so one slow upstream cannot delay heartbeat monitoring.
    Executor recoveryExecutor = Executors.newFixedThreadPool(2, runnable -> {
        var thread = new Thread(runnable, "device-recovery");
        thread.setDaemon(true);
        return thread;
    });
    final Set<String> recoveryInFlight = ConcurrentHashMap.newKeySet();

    public boolean recordHeartbeat(String deviceToken, Integer status, String assignmentId,
                                   String clientVersion, LocalDateTime now) {
        if (deviceToken == null || deviceToken.isBlank()) {
            return true;
        }
        synchronized (deviceToken.intern()) {
            var runtime = runtimeMapper.selectById(deviceToken);
            if (runtime != null && "REMOVED".equals(runtime.getState())) {
                requestHalt(deviceToken);
                return true;
            }
            if (!isActiveDevice(deviceToken, now)) {
                requestHalt(deviceToken);
                return true;
            }

            // Startup may have seeded an offline row while this request was being accepted.
            runtime = runtimeMapper.selectById(deviceToken);
            var exists = runtime != null;
            var wasOnline = runtime != null && "ONLINE".equals(runtime.getState());
            // A freshly initialized row has offlineSince but no heartbeat. It is not a recovery.
            var wasOffline = runtime != null && "OFFLINE".equals(runtime.getState())
                    && (runtime.getLastHeartbeatAt() != null || runtime.getOfflineSince() == null);
            if (runtime == null) {
                runtime = new DeviceRuntimeEntity()
                        .setDeviceToken(deviceToken)
                        .setLastNoticeLevel(0)
                        .setRecoveryPending(0)
                        .setConsecutiveFailures(0);
            }
            var onlineHeartbeat = status == null || status != 0;
            if (onlineHeartbeat) {
                runtime.setState("ONLINE")
                        .setLastHeartbeatAt(now)
                        .setOfflineSince(null)
                        .setLastNoticeLevel(0)
                        .setLastNoticeAt(null)
                        .setRecoveryPending(wasOffline ? 1 : runtime.getRecoveryPending())
                        .setClientVersion(clientVersion)
                        .setUpdatedAt(now);
            } else {
                var offlineSince = runtime.getOfflineSince() == null ? now : runtime.getOfflineSince();
                runtime.setState("OFFLINE")
                        .setLastHeartbeatAt(now)
                        .setOfflineSince(offlineSince)
                        .setClientVersion(clientVersion)
                        .setUpdatedAt(now);
            }
            saveRuntime(runtime, exists);

            dynamicInfo.getDeviceCounterMap().put(deviceToken, 3);
            dynamicInfo.getDeviceStatusMap().put(deviceToken, status == null ? 1 : status);
            dynamicInfo.getDeviceLastHeartbeatMap().put(deviceToken, now);

            if (!onlineHeartbeat && wasOnline) {
                submitRecovery(deviceToken, now);
            }

            // A heartbeat is also the first safe point at which a stale client can be stopped.
            synchronized (dynamicInfo.getHaltList()) {
                return dynamicInfo.getHaltList().contains(deviceToken);
            }
        }
    }

    public void initializeDevice(DeviceEntity device, LocalDateTime now) {
        if (device == null || device.getDeviceToken() == null) {
            return;
        }
        var deviceToken = device.getDeviceToken();
        synchronized (deviceToken.intern()) {
            initializeDeviceMemory(deviceToken);
            if (runtimeMapper.selectById(deviceToken) != null) {
                return;
            }
            var runtime = new DeviceRuntimeEntity()
                    .setDeviceToken(deviceToken)
                    .setState("OFFLINE")
                    .setOfflineSince(now)
                    .setLastNoticeLevel(0)
                    .setRecoveryPending(0)
                    .setConsecutiveFailures(0)
                    .setLastFailureNoticeCount(0)
                    .setUpdatedAt(now);
            try {
                runtimeMapper.insert(runtime);
            } catch (DuplicateKeyException duplicateKeyException) {
                log.debug("设备运行时记录已由并发心跳创建：{}", deviceToken);
            }
        }
    }

    public boolean hasFreshHeartbeat(String deviceToken, LocalDateTime now) {
        if (deviceToken == null || deviceToken.isBlank() || now == null || runtimeMapper == null) {
            return false;
        }
        var runtime = runtimeMapper.selectById(deviceToken);
        return runtime != null
                && "ONLINE".equals(runtime.getState())
                && !isHeartbeatExpired(runtime, now);
    }

    public boolean isSuspended(String deviceToken, LocalDateTime now) {
        var runtime = runtimeMapper.selectById(deviceToken);
        return runtime != null && runtime.getSuspendedUntil() != null
                && runtime.getSuspendedUntil().isAfter(now);
    }

    public boolean recordTaskFailure(String deviceToken, LocalDateTime now) {
        if (deviceToken == null || deviceToken.isBlank()) {
            return false;
        }
        synchronized (deviceToken.intern()) {
            var runtime = runtimeMapper.selectById(deviceToken);
            var exists = runtime != null;
            if (runtime == null) {
                runtime = new DeviceRuntimeEntity().setDeviceToken(deviceToken)
                        .setConsecutiveFailures(0).setLastFailureNoticeCount(0);
            }
            var failures = runtime.getConsecutiveFailures() == null ? 0 : runtime.getConsecutiveFailures();
            failures++;
            var shouldNotify = failures >= 3
                    && (runtime.getLastFailureNoticeCount() == null || runtime.getLastFailureNoticeCount() < 3);
            runtime.setConsecutiveFailures(failures).setUpdatedAt(now);
            if (shouldNotify) {
                runtime.setLastFailureNoticeCount(3)
                        .setLastFailureNoticeAt(now)
                        .setSuspendedUntil(now.plusHours(1));
            }
            saveRuntime(runtime, exists);
            if (shouldNotify) {
                var device = deviceMapper.selectOne(Wrappers.<DeviceEntity>lambdaQuery()
                        .eq(DeviceEntity::getDeviceToken, deviceToken));
                if (ImportantDevicePolicy.includes(device)) {
                    messageService.pushAdmin(ImportantDevicePolicy.NOTICE_PREFIX + " 设备异常",
                            "设备 " + deviceName(device) + " 连续失败3次，已暂停1小时，期间不会继续分配新任务。");
                }
            }
            return shouldNotify;
        }
    }

    public void recordTaskSuccess(String deviceToken, LocalDateTime now) {
        if (deviceToken == null || deviceToken.isBlank()) {
            return;
        }
        var runtime = runtimeMapper.selectById(deviceToken);
        if (runtime == null) {
            return;
        }
        runtime.setConsecutiveFailures(0)
                .setLastFailureNoticeCount(0)
                .setLastFailureNoticeAt(null)
                .setSuspendedUntil(null)
                .setUpdatedAt(now);
        saveRuntime(runtime, true);
    }

    public ScanResult scan(LocalDateTime now) {
        var devices = deviceMapper.selectList(Wrappers.<DeviceEntity>lambdaQuery()
                .eq(DeviceEntity::getDelete, 0));
        var offlineNotices = new ArrayList<Notice>();
        var recoveryNotices = new ArrayList<DeviceEntity>();
        var removedDevices = new ArrayList<DeviceEntity>();

        for (DeviceEntity device : devices) {
            var token = device.getDeviceToken();
            if (token == null || token.isBlank()) {
                continue;
            }
            var runtime = runtimeMapper.selectById(token);
            if (runtime == null) {
                continue;
            }

            if ("ONLINE".equals(runtime.getState()) && isHeartbeatExpired(runtime, now)) {
                var offlineSince = runtime.getLastHeartbeatAt() == null ? now : runtime.getLastHeartbeatAt();
                runtime.setState("OFFLINE")
                        .setOfflineSince(offlineSince)
                        .setLastNoticeLevel(0)
                        .setLastNoticeAt(null)
                        .setRecoveryPending(0)
                        .setUpdatedAt(now);
                saveRuntime(runtime, true);
                dynamicInfo.getDeviceStatusMap().put(token, 0);
                submitRecovery(token, now);
            }

            if ("OFFLINE".equals(runtime.getState())) {
                var offlineSince = runtime.getOfflineSince() == null ? now : runtime.getOfflineSince();
                runtime.setOfflineSince(offlineSince);
                var offlineMinutes = Math.max(0, Duration.between(offlineSince, now).toMinutes());
                if (!offlineSince.plus(SOFT_DELETE_AFTER).isAfter(now)) {
                    device.setDelete(1);
                    if (deviceMapper.updateById(device) != 1) {
                        device.setDelete(0);
                        log.warn("设备软删除失败，保留设备运行时状态：{}", token);
                        continue;
                    }
                    dynamicInfo.getDeviceStatusMap().remove(token);
                    dynamicInfo.getDeviceCounterMap().remove(token);
                    dynamicInfo.getDeviceLastHeartbeatMap().remove(token);
                    submitRecovery(token, now);
                    removedDevices.add(device);
                    runtime.setState("REMOVED").setUpdatedAt(now);
                    saveRuntime(runtime, true);
                    continue;
                }
                var nextLevel = DeviceNoticeSchedule.nextNoticeLevel(offlineMinutes,
                        runtime.getLastNoticeLevel() == null ? 0 : runtime.getLastNoticeLevel());
                if (nextLevel > 0) {
                    runtime.setLastNoticeLevel(nextLevel)
                            .setLastNoticeAt(now)
                            .setUpdatedAt(now);
                    saveRuntime(runtime, true);
                    offlineNotices.add(new Notice(device, nextLevel));
                }
            } else if (runtime.getRecoveryPending() != null && runtime.getRecoveryPending() == 1) {
                recoveryNotices.add(device);
                runtime.setRecoveryPending(0).setUpdatedAt(now);
                saveRuntime(runtime, true);
            }
        }

        sendOfflineNotice(offlineNotices);
        sendRecoveryNotice(recoveryNotices);
        if (!removedDevices.isEmpty()) {
            var names = new ArrayList<String>();
            removedDevices.forEach(device -> {
                if (ImportantDevicePolicy.includes(device)) {
                    names.add(deviceName(device));
                }
            });
            if (!names.isEmpty()) {
                messageService.pushAdmin(ImportantDevicePolicy.NOTICE_PREFIX + " 设备移除",
                        "已移除设备：" + String.join("、", names));
            }
        }
        return new ScanResult(offlineNotices, recoveryNotices, removedDevices);
    }

    private boolean isActiveDevice(String deviceToken, LocalDateTime now) {
        if (deviceMapper == null) {
            return false;
        }
        var device = deviceMapper.selectOne(Wrappers.<DeviceEntity>lambdaQuery()
                .eq(DeviceEntity::getDeviceToken, deviceToken)
                .eq(DeviceEntity::getDelete, 0)
                .last("LIMIT 1"));
        if (device == null) {
            return false;
        }
        initializeDeviceMemory(device.getDeviceToken());
        return true;
    }

    private void initializeDeviceMemory(String deviceToken) {
        dynamicInfo.getDeviceStatusMap().putIfAbsent(deviceToken, 0);
        dynamicInfo.getDeviceCounterMap().putIfAbsent(deviceToken, 3);
    }

    private void requestHalt(String deviceToken) {
        synchronized (dynamicInfo.getHaltList()) {
            if (!dynamicInfo.getHaltList().contains(deviceToken)) {
                dynamicInfo.getHaltList().add(deviceToken);
            }
        }
    }

    private void submitRecovery(String deviceToken, LocalDateTime now) {
        if (!recoveryInFlight.add(deviceToken)) {
            return;
        }
        try {
            recoveryExecutor.execute(() -> {
                try {
                    taskRecoveryService.recoverDeviceOffline(deviceToken, now);
                } catch (Exception exception) {
                    log.warn("设备离线任务回收失败，设备 {}", deviceToken, exception);
                } finally {
                    recoveryInFlight.remove(deviceToken);
                }
            });
        } catch (RuntimeException exception) {
            recoveryInFlight.remove(deviceToken);
            log.warn("设备离线任务回收提交失败，设备 {}", deviceToken, exception);
        }
    }

    @PreDestroy
    void shutdownRecoveryExecutor() {
        if (recoveryExecutor instanceof ExecutorService) {
            ((ExecutorService) recoveryExecutor).shutdownNow();
        }
    }

    private boolean isHeartbeatExpired(DeviceRuntimeEntity runtime, LocalDateTime now) {
        var lastHeartbeatAt = runtime.getLastHeartbeatAt();
        return lastHeartbeatAt == null || !lastHeartbeatAt.plus(HEARTBEAT_GRACE).isAfter(now);
    }

    private void sendOfflineNotice(List<Notice> notices) {
        var importantNotices = new ArrayList<Notice>();
        notices.forEach(notice -> {
            if (ImportantDevicePolicy.includes(notice.device)) {
                importantNotices.add(notice);
            }
        });
        if (importantNotices.isEmpty()) {
            return;
        }
        var content = new StringBuilder("重点设备异常：\n");
        var names = new ArrayList<String>();
        importantNotices.forEach(notice -> content.append(deviceName(notice.device))
                .append("（已离线 ").append(notice.level).append(" 分钟）\n"));
        importantNotices.forEach(notice -> names.add(deviceName(notice.device)));
        messageService.pushAdmin(ImportantDevicePolicy.NOTICE_PREFIX + " 设备异常："
                + String.join("、", names), content.toString());
    }

    private void sendRecoveryNotice(List<DeviceEntity> devices) {
        var names = new ArrayList<String>();
        devices.forEach(device -> {
            if (ImportantDevicePolicy.includes(device)) {
                names.add(deviceName(device));
            }
        });
        if (!names.isEmpty()) {
            messageService.pushAdmin(ImportantDevicePolicy.NOTICE_PREFIX + " 设备恢复",
                    "已恢复设备：" + String.join("、", names));
        }
    }

    private String deviceName(DeviceEntity device) {
        if (device == null) {
            return "未知设备";
        }
        var name = device.getDeviceName();
        return name == null || name.isBlank() ? device.getDeviceToken() : name;
    }

    private void saveRuntime(DeviceRuntimeEntity runtime, boolean exists) {
        if (exists) {
            runtimeMapper.updateById(runtime);
        } else {
            runtimeMapper.insert(runtime);
        }
    }

    public static final class Notice {
        private final DeviceEntity device;
        private final int level;

        public Notice(DeviceEntity device, int level) {
            this.device = device;
            this.level = level;
        }

        public DeviceEntity getDevice() {
            return device;
        }

        public int getLevel() {
            return level;
        }
    }

    public static final class ScanResult {
        private final List<Notice> offlineNotices;
        private final List<DeviceEntity> recoveryDevices;
        private final List<DeviceEntity> removedDevices;

        public ScanResult(List<Notice> offlineNotices, List<DeviceEntity> recoveryDevices,
                          List<DeviceEntity> removedDevices) {
            this.offlineNotices = offlineNotices;
            this.recoveryDevices = recoveryDevices;
            this.removedDevices = removedDevices;
        }

        public List<Notice> getOfflineNotices() {
            return offlineNotices;
        }

        public List<DeviceEntity> getRecoveryDevices() {
            return recoveryDevices;
        }

        public List<DeviceEntity> getRemovedDevices() {
            return removedDevices;
        }
    }
}
