package moe.dazecake.inquisition.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import moe.dazecake.inquisition.mapper.AccountMapper;
import moe.dazecake.inquisition.mapper.DeviceMapper;
import moe.dazecake.inquisition.mapper.DeviceRuntimeMapper;
import moe.dazecake.inquisition.model.entity.AccountEntity;
import moe.dazecake.inquisition.model.entity.DeviceEntity;
import moe.dazecake.inquisition.model.entity.DeviceRuntimeEntity;
import moe.dazecake.inquisition.model.entity.TaskAssignmentEntity;
import moe.dazecake.inquisition.model.vo.device.DeviceRuntimeProjection;
import moe.dazecake.inquisition.model.vo.task.RunningTaskVO;
import moe.dazecake.inquisition.utils.GameDayClock;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class DeviceRuntimeProjectionService {
    public static final String OFFLINE = "OFFLINE";
    public static final String SUSPENDED = "SUSPENDED";
    public static final String BUSY = "BUSY";
    public static final String IDLE = "IDLE";

    @Resource
    DeviceMapper deviceMapper;

    @Resource
    DeviceRuntimeMapper deviceRuntimeMapper;

    @Resource
    TaskAssignmentService taskAssignmentService;

    @Resource
    AccountMapper accountMapper;

    public List<DeviceRuntimeProjection> project(LocalDateTime requestedNow) {
        var now = requestedNow == null ? GameDayClock.now() : requestedNow;
        var loadedAssignments = taskAssignmentService.findAll();
        var assignments = loadedAssignments == null ? List.<TaskAssignmentEntity>of()
                : loadedAssignments;
        var accountIds = assignments.stream()
                .map(assignment -> assignment.getAccountId())
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, AccountEntity> accountById = new HashMap<>();
        if (!accountIds.isEmpty()) {
            var accounts = accountMapper.selectBatchIds(accountIds);
            if (accounts != null) {
                accounts.forEach(account -> accountById.put(account.getId(), account));
            }
        }
        Map<String, AccountSummary> currentByDevice = new HashMap<>();
        assignments.forEach(assignment -> {
            if (assignment.getDeviceToken() == null || assignment.getDeviceToken().isBlank()) {
                return;
            }
            var account = accountById.get(assignment.getAccountId());
            currentByDevice.putIfAbsent(assignment.getDeviceToken(), new AccountSummary(
                    assignment.getAccountId(), account == null ? null : account.getName()));
        });
        return project(now, currentByDevice);
    }

    public List<DeviceRuntimeProjection> project(LocalDateTime requestedNow, List<RunningTaskVO> runningTasks) {
        var now = requestedNow == null ? GameDayClock.now() : requestedNow;
        Map<String, AccountSummary> currentByDevice = new HashMap<>();
        if (runningTasks != null) {
            runningTasks.forEach(task -> {
                if (task != null && task.getDeviceToken() != null && !task.getDeviceToken().isBlank()) {
                    currentByDevice.putIfAbsent(task.getDeviceToken(),
                            new AccountSummary(task.getAccountId(), task.getName()));
                }
            });
        }
        return project(now, currentByDevice);
    }

    private List<DeviceRuntimeProjection> project(LocalDateTime now,
                                                   Map<String, AccountSummary> currentByDevice) {
        var devices = deviceMapper.selectList(Wrappers.<DeviceEntity>lambdaQuery()
                .eq(DeviceEntity::getDelete, 0));
        if (devices == null || devices.isEmpty()) {
            return List.of();
        }
        var tokens = devices.stream().map(DeviceEntity::getDeviceToken)
                .filter(Objects::nonNull).filter(token -> !token.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, DeviceRuntimeEntity> runtimeByToken = new HashMap<>();
        if (!tokens.isEmpty()) {
            var runtimes = deviceRuntimeMapper.selectList(Wrappers.<DeviceRuntimeEntity>lambdaQuery()
                    .in(DeviceRuntimeEntity::getDeviceToken, tokens));
            if (runtimes != null) {
                runtimeByToken.putAll(runtimes.stream().filter(Objects::nonNull)
                        .filter(runtime -> runtime.getDeviceToken() != null)
                        .collect(Collectors.toMap(DeviceRuntimeEntity::getDeviceToken,
                                Function.identity(), (left, right) -> left)));
            }
        }

        var result = new ArrayList<DeviceRuntimeProjection>();
        devices.forEach(device -> {
            var runtime = runtimeByToken.get(device.getDeviceToken());
            var current = currentByDevice.get(device.getDeviceToken());
            var state = runtimeState(runtime, current != null, now);
            var offlineSince = runtime == null ? null : runtime.getOfflineSince();
            if (OFFLINE.equals(state) && offlineSince == null && runtime != null) {
                offlineSince = runtime.getLastHeartbeatAt();
            }
            result.add(new DeviceRuntimeProjection()
                    .setDevice(device)
                    .setRuntimeState(state)
                    .setLastHeartbeatAt(runtime == null ? null : runtime.getLastHeartbeatAt())
                    .setOfflineSince(offlineSince)
                    .setSuspendedUntil(runtime == null ? null : runtime.getSuspendedUntil())
                    .setCurrentAccountId(current == null ? null : current.id)
                    .setCurrentAccountName(current == null ? null : current.name));
        });
        return result;
    }

    private String runtimeState(DeviceRuntimeEntity runtime, boolean busy, LocalDateTime now) {
        if (!hasFreshHeartbeat(runtime, now)) {
            return OFFLINE;
        }
        if (runtime.getSuspendedUntil() != null && runtime.getSuspendedUntil().isAfter(now)) {
            return SUSPENDED;
        }
        return busy ? BUSY : IDLE;
    }

    private boolean hasFreshHeartbeat(DeviceRuntimeEntity runtime, LocalDateTime now) {
        return runtime != null
                && "ONLINE".equals(runtime.getState())
                && runtime.getLastHeartbeatAt() != null
                && runtime.getLastHeartbeatAt().plus(DeviceRuntimeService.HEARTBEAT_GRACE).isAfter(now);
    }

    private static final class AccountSummary {
        private final Long id;
        private final String name;

        private AccountSummary(Long id, String name) {
            this.id = id;
            this.name = name;
        }
    }
}
