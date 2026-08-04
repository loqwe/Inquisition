package moe.dazecake.inquisition.service.impl;

import moe.dazecake.inquisition.mapper.AccountMapper;
import moe.dazecake.inquisition.mapper.DeviceMapper;
import moe.dazecake.inquisition.mapper.DeviceRuntimeMapper;
import moe.dazecake.inquisition.model.entity.AccountEntity;
import moe.dazecake.inquisition.model.entity.DeviceEntity;
import moe.dazecake.inquisition.model.entity.DeviceRuntimeEntity;
import moe.dazecake.inquisition.model.entity.TaskAssignmentEntity;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeviceRuntimeProjectionServiceTest {

    @Test
    void projectsFourMutuallyExclusiveRuntimeStatesFromHeartbeatSuspensionAndAssignments() {
        var service = new DeviceRuntimeProjectionService();
        service.deviceMapper = mock(DeviceMapper.class);
        service.deviceRuntimeMapper = mock(DeviceRuntimeMapper.class);
        service.taskAssignmentService = mock(TaskAssignmentService.class);
        service.accountMapper = mock(AccountMapper.class);
        var now = LocalDateTime.of(2026, 7, 29, 10, 0);
        when(service.deviceMapper.selectList(any())).thenReturn(List.of(
                device(1L, "offline"), device(2L, "suspended"),
                device(3L, "busy"), device(4L, "idle")));
        when(service.deviceRuntimeMapper.selectList(any())).thenReturn(List.of(
                runtime("offline", now.minusMinutes(30)),
                runtime("suspended", now.minusMinutes(1)).setSuspendedUntil(now.plusMinutes(20)),
                runtime("busy", now.minusMinutes(1)),
                runtime("idle", now.minusMinutes(1))));
        when(service.taskAssignmentService.findAll()).thenReturn(List.of(
                new TaskAssignmentEntity().setAssignmentId("assignment-7")
                        .setAccountId(7L).setDeviceToken("busy")));
        when(service.accountMapper.selectBatchIds(any())).thenReturn(List.of(
                new AccountEntity().setId(7L).setName("账号7")));

        var projections = service.project(now);
        Map<String, String> states = projections.stream().collect(Collectors.toMap(
                projection -> projection.getDevice().getDeviceToken(),
                projection -> projection.getRuntimeState()));

        assertEquals(DeviceRuntimeProjectionService.OFFLINE, states.get("offline"));
        assertEquals(DeviceRuntimeProjectionService.SUSPENDED, states.get("suspended"));
        assertEquals(DeviceRuntimeProjectionService.BUSY, states.get("busy"));
        assertEquals(DeviceRuntimeProjectionService.IDLE, states.get("idle"));
        assertEquals(3, projections.stream().filter(projection -> projection.isOnline()).count());
        assertEquals("账号7", projections.stream()
                .filter(projection -> "busy".equals(projection.getDevice().getDeviceToken()))
                .findFirst().orElseThrow().getCurrentAccountName());
    }

    private static DeviceEntity device(Long id, String token) {
        return new DeviceEntity().setId(id).setDeviceName(token).setDeviceToken(token).setDelete(0);
    }

    private static DeviceRuntimeEntity runtime(String token, LocalDateTime heartbeat) {
        return new DeviceRuntimeEntity().setDeviceToken(token).setState("ONLINE")
                .setLastHeartbeatAt(heartbeat);
    }
}
