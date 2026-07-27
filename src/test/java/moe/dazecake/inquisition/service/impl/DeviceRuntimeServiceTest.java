package moe.dazecake.inquisition.service.impl;

import moe.dazecake.inquisition.mapper.DeviceMapper;
import moe.dazecake.inquisition.mapper.DeviceRuntimeMapper;
import moe.dazecake.inquisition.model.entity.DeviceEntity;
import moe.dazecake.inquisition.model.entity.DeviceRuntimeEntity;
import moe.dazecake.inquisition.utils.DynamicInfo;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class DeviceRuntimeServiceTest {

    @Test
    void startupLeavesPreviouslyUnseenDeviceOfflineUntilHeartbeat() {
        var service = new DeviceRuntimeService();
        service.runtimeMapper = mock(DeviceRuntimeMapper.class);
        service.dynamicInfo = new DynamicInfo();
        var now = LocalDateTime.of(2026, 7, 19, 12, 0);
        var device = new DeviceEntity().setDeviceToken("device-1").setDelete(0);

        service.initializeDevice(device, now);

        var runtime = org.mockito.ArgumentCaptor.forClass(DeviceRuntimeEntity.class);
        verify(service.runtimeMapper).insert(runtime.capture());
        assertEquals("OFFLINE", runtime.getValue().getState());
        assertNull(runtime.getValue().getLastHeartbeatAt());
        assertEquals(now, runtime.getValue().getOfflineSince());
        assertEquals(0, runtime.getValue().getRecoveryPending());
        assertEquals(3, service.dynamicInfo.getDeviceCounterMap().get("device-1"));
    }

    @Test
    void taskAdmissionRequiresFreshOnlineHeartbeat() {
        var service = new DeviceRuntimeService();
        service.runtimeMapper = mock(DeviceRuntimeMapper.class);
        var now = LocalDateTime.of(2026, 7, 19, 12, 0);
        when(service.runtimeMapper.selectById("missing")).thenReturn(null);
        when(service.runtimeMapper.selectById("offline")).thenReturn(new DeviceRuntimeEntity()
                .setDeviceToken("offline")
                .setState("OFFLINE")
                .setLastHeartbeatAt(now.minusSeconds(1)));
        when(service.runtimeMapper.selectById("no-heartbeat")).thenReturn(new DeviceRuntimeEntity()
                .setDeviceToken("no-heartbeat")
                .setState("ONLINE"));
        when(service.runtimeMapper.selectById("stale")).thenReturn(new DeviceRuntimeEntity()
                .setDeviceToken("stale")
                .setState("ONLINE")
                .setLastHeartbeatAt(now.minusMinutes(30)));
        when(service.runtimeMapper.selectById("fresh")).thenReturn(new DeviceRuntimeEntity()
                .setDeviceToken("fresh")
                .setState("ONLINE")
                .setLastHeartbeatAt(now.minusMinutes(29).minusSeconds(59)));

        assertFalse(service.hasFreshHeartbeat("missing", now));
        assertFalse(service.hasFreshHeartbeat("offline", now));
        assertFalse(service.hasFreshHeartbeat("no-heartbeat", now));
        assertFalse(service.hasFreshHeartbeat("stale", now));
        assertTrue(service.hasFreshHeartbeat("fresh", now));
    }

    @Test
    void heartbeatFromAnOfflineDeviceMarksRecoveryWithoutResettingTaskState() {
        var service = new DeviceRuntimeService();
        service.runtimeMapper = mock(DeviceRuntimeMapper.class);
        service.deviceMapper = mock(DeviceMapper.class);
        service.dynamicInfo = new DynamicInfo();
        when(service.deviceMapper.selectOne(any())).thenReturn(new DeviceEntity()
                .setDeviceToken("device-1").setDelete(0));
        var runtime = new DeviceRuntimeEntity()
                .setDeviceToken("device-1")
                .setState("OFFLINE")
                .setLastNoticeLevel(15)
                .setRecoveryPending(0);
        when(service.runtimeMapper.selectById("device-1")).thenReturn(runtime);

        assertTrue(!service.recordHeartbeat("device-1", 1001, "assignment-1", "1.0",
                LocalDateTime.of(2026, 7, 19, 12, 0)));

        assertEquals("ONLINE", runtime.getState());
        assertEquals(1, runtime.getRecoveryPending());
        assertEquals(3, service.dynamicInfo.getDeviceCounterMap().get("device-1"));
        verify(service.runtimeMapper).updateById(runtime);
    }

    @Test
    void offlineHeartbeatDoesNotMarkDeviceOnline() {
        var service = new DeviceRuntimeService();
        service.runtimeMapper = mock(DeviceRuntimeMapper.class);
        service.deviceMapper = mock(DeviceMapper.class);
        service.dynamicInfo = new DynamicInfo();
        service.taskRecoveryService = mock(TaskRecoveryService.class);
        service.recoveryExecutor = Runnable::run;
        when(service.deviceMapper.selectOne(any())).thenReturn(new DeviceEntity()
                .setDeviceToken("device-1").setDelete(0));
        var runtime = new DeviceRuntimeEntity()
                .setDeviceToken("device-1")
                .setState("ONLINE")
                .setRecoveryPending(0);
        when(service.runtimeMapper.selectById("device-1")).thenReturn(runtime);
        var now = LocalDateTime.of(2026, 7, 19, 12, 0);

        assertFalse(service.recordHeartbeat("device-1", 0, null, "1.0", now));

        assertEquals("OFFLINE", runtime.getState());
        assertEquals(0, service.dynamicInfo.getDeviceStatusMap().get("device-1"));
        verify(service.runtimeMapper).updateById(runtime);
        verify(service.taskRecoveryService).recoverDeviceOffline("device-1", now);
    }

    @Test
    void heartbeatHaltLookupUsesHaltListLock() {
        var service = new DeviceRuntimeService();
        service.runtimeMapper = mock(DeviceRuntimeMapper.class);
        service.deviceMapper = mock(DeviceMapper.class);
        service.dynamicInfo = new DynamicInfo();
        var haltList = new LockCheckingHaltList();
        haltList.add("device-1");
        service.dynamicInfo.setHaltList(haltList);
        when(service.deviceMapper.selectOne(any())).thenReturn(new DeviceEntity()
                .setDeviceToken("device-1").setDelete(0));
        when(service.runtimeMapper.selectById("device-1")).thenReturn(new DeviceRuntimeEntity()
                .setDeviceToken("device-1").setState("ONLINE").setRecoveryPending(0));

        assertTrue(service.recordHeartbeat("device-1", 1001, null, "1.0",
                LocalDateTime.of(2026, 7, 19, 12, 0)));
    }

    @Test
    void heartbeatFromADeletedDeviceRequestsShutdownWithoutRevivingRuntime() {
        var service = new DeviceRuntimeService();
        service.runtimeMapper = mock(DeviceRuntimeMapper.class);
        service.deviceMapper = mock(DeviceMapper.class);
        service.dynamicInfo = new DynamicInfo();
        when(service.deviceMapper.selectOne(any())).thenReturn(null);

        assertTrue(service.recordHeartbeat("deleted-device", 1001, null, "1.0",
                LocalDateTime.of(2026, 7, 19, 12, 0)));

        verify(service.runtimeMapper, times(1)).selectById("deleted-device");
        verify(service.runtimeMapper, times(0)).insert(any());
        verify(service.runtimeMapper, times(0)).updateById(any());
        assertTrue(service.dynamicInfo.getHaltList().contains("deleted-device"));
    }

    @Test
    void scanKeepsDeviceOnlineBeforeThirtyMinutesWithoutHeartbeat() {
        var service = new DeviceRuntimeService();
        service.runtimeMapper = mock(DeviceRuntimeMapper.class);
        service.deviceMapper = mock(DeviceMapper.class);
        service.dynamicInfo = new DynamicInfo();
        service.taskRecoveryService = mock(TaskRecoveryService.class);
        service.messageService = mock(MessageServiceImpl.class);
        service.recoveryExecutor = Runnable::run;
        var now = LocalDateTime.of(2026, 7, 19, 12, 0);
        var device = new DeviceEntity().setDeviceToken("device-1").setDelete(0);
        var runtime = new DeviceRuntimeEntity().setDeviceToken("device-1")
                .setState("ONLINE").setLastHeartbeatAt(now.minusMinutes(29).minusSeconds(59));
        when(service.deviceMapper.selectList(any())).thenReturn(List.of(device));
        when(service.runtimeMapper.selectById("device-1")).thenReturn(runtime);

        var result = service.scan(now);

        assertEquals("ONLINE", runtime.getState());
        assertTrue(result.getOfflineNotices().isEmpty());
        verify(service.taskRecoveryService, never()).recoverDeviceOffline(any(), any());
    }

    @Test
    void thirtyMinuteHeartbeatExpiryRecoversOnceAndStartsAtLastHeartbeat() {
        var service = new DeviceRuntimeService();
        service.runtimeMapper = mock(DeviceRuntimeMapper.class);
        service.deviceMapper = mock(DeviceMapper.class);
        service.dynamicInfo = new DynamicInfo();
        service.taskRecoveryService = mock(TaskRecoveryService.class);
        service.messageService = mock(MessageServiceImpl.class);
        service.recoveryExecutor = Runnable::run;
        var now = LocalDateTime.of(2026, 7, 19, 12, 0);
        var lastHeartbeat = now.minusMinutes(30);
        var device = new DeviceEntity().setDeviceName("A").setDeviceToken("device-1").setDelete(0);
        var runtime = new DeviceRuntimeEntity().setDeviceToken("device-1")
                .setState("ONLINE").setLastHeartbeatAt(lastHeartbeat);
        when(service.deviceMapper.selectList(any())).thenReturn(List.of(device));
        when(service.runtimeMapper.selectById("device-1")).thenReturn(runtime);

        var firstScan = service.scan(now);
        var secondScan = service.scan(now.plusMinutes(5));

        verify(service.taskRecoveryService, times(1)).recoverDeviceOffline("device-1", now);
        assertEquals("OFFLINE", runtime.getState());
        assertEquals(lastHeartbeat, runtime.getOfflineSince());
        assertEquals(30, runtime.getLastNoticeLevel());
        assertEquals(1, firstScan.getOfflineNotices().size());
        assertTrue(secondScan.getOfflineNotices().isEmpty());
    }

    @Test
    void offlineNoticeAggregatesDeviceNamesIntoOnePush() {
        var service = new DeviceRuntimeService();
        service.runtimeMapper = mock(DeviceRuntimeMapper.class);
        service.deviceMapper = mock(DeviceMapper.class);
        service.dynamicInfo = new DynamicInfo();
        service.taskRecoveryService = mock(TaskRecoveryService.class);
        service.messageService = mock(MessageServiceImpl.class);
        var now = LocalDateTime.of(2026, 7, 19, 12, 10);
        var deviceA = new DeviceEntity().setDeviceName("A").setDeviceToken("a").setDelete(0);
        var deviceB = new DeviceEntity().setDeviceName("1").setDeviceToken("b").setDelete(0);
        when(service.deviceMapper.selectList(any())).thenReturn(List.of(deviceA, deviceB));
        when(service.runtimeMapper.selectById("a")).thenReturn(new DeviceRuntimeEntity()
                .setDeviceToken("a").setState("OFFLINE")
                .setOfflineSince(now.minusMinutes(30)).setLastNoticeLevel(0));
        when(service.runtimeMapper.selectById("b")).thenReturn(new DeviceRuntimeEntity()
                .setDeviceToken("b").setState("OFFLINE")
                .setOfflineSince(now.minusMinutes(30)).setLastNoticeLevel(0));

        var result = service.scan(now);

        assertEquals(2, result.getOfflineNotices().size());
        verify(service.messageService, times(1)).pushAdmin(eq("[审判庭重点设备] 设备异常：A、1"),
                org.mockito.ArgumentMatchers.argThat(content -> content.contains("A")
                        && content.contains("1")));
    }

    @Test
    void offlineNoticeIgnoresNonImportantDevicesWithoutSkippingTheirStateUpdate() {
        var service = new DeviceRuntimeService();
        service.runtimeMapper = mock(DeviceRuntimeMapper.class);
        service.deviceMapper = mock(DeviceMapper.class);
        service.dynamicInfo = new DynamicInfo();
        service.taskRecoveryService = mock(TaskRecoveryService.class);
        service.messageService = mock(MessageServiceImpl.class);
        var now = LocalDateTime.of(2026, 7, 19, 12, 10);
        var device = new DeviceEntity().setDeviceName("B").setDeviceToken("b").setDelete(0);
        var runtime = new DeviceRuntimeEntity().setDeviceToken("b").setState("OFFLINE")
                .setOfflineSince(now.minusMinutes(30)).setLastNoticeLevel(0);
        when(service.deviceMapper.selectList(any())).thenReturn(List.of(device));
        when(service.runtimeMapper.selectById("b")).thenReturn(runtime);

        var result = service.scan(now);

        assertEquals(1, result.getOfflineNotices().size());
        assertEquals(30, runtime.getLastNoticeLevel());
        verify(service.messageService, never()).pushAdmin(any(), any());
    }

    @Test
    void recoveryNoticeOnlyReportsImportantDevices() {
        var service = new DeviceRuntimeService();
        service.runtimeMapper = mock(DeviceRuntimeMapper.class);
        service.deviceMapper = mock(DeviceMapper.class);
        service.dynamicInfo = new DynamicInfo();
        service.taskRecoveryService = mock(TaskRecoveryService.class);
        service.messageService = mock(MessageServiceImpl.class);
        var now = LocalDateTime.of(2026, 7, 19, 12, 10);
        var deviceA = new DeviceEntity().setDeviceName("A").setDeviceToken("a").setDelete(0);
        var deviceB = new DeviceEntity().setDeviceName("B").setDeviceToken("b").setDelete(0);
        when(service.deviceMapper.selectList(any())).thenReturn(List.of(deviceA, deviceB));
        when(service.runtimeMapper.selectById("a")).thenReturn(new DeviceRuntimeEntity()
                .setDeviceToken("a").setState("ONLINE").setLastHeartbeatAt(now)
                .setRecoveryPending(1));
        when(service.runtimeMapper.selectById("b")).thenReturn(new DeviceRuntimeEntity()
                .setDeviceToken("b").setState("ONLINE").setLastHeartbeatAt(now)
                .setRecoveryPending(1));

        var result = service.scan(now);

        assertEquals(2, result.getRecoveryDevices().size());
        verify(service.messageService).pushAdmin(eq("[审判庭重点设备] 设备恢复"),
                org.mockito.ArgumentMatchers.argThat(content -> content.contains("A")
                        && !content.contains("B")));
    }

    @Test
    void exactlyTwentyFourHoursOfflineTriggersSoftRemoval() {
        var service = new DeviceRuntimeService();
        service.runtimeMapper = mock(DeviceRuntimeMapper.class);
        service.deviceMapper = mock(DeviceMapper.class);
        service.dynamicInfo = new DynamicInfo();
        service.taskRecoveryService = mock(TaskRecoveryService.class);
        service.messageService = mock(MessageServiceImpl.class);
        service.recoveryExecutor = Runnable::run;
        var now = LocalDateTime.of(2026, 7, 19, 12, 0);
        var device = new DeviceEntity().setDeviceName("设备A").setDeviceToken("a").setDelete(0);
        var runtime = new DeviceRuntimeEntity().setDeviceToken("a").setState("OFFLINE")
                .setOfflineSince(now.minusHours(24)).setLastNoticeLevel(60);
        when(service.deviceMapper.selectList(any())).thenReturn(List.of(device));
        when(service.deviceMapper.updateById(device)).thenReturn(1);
        when(service.runtimeMapper.selectById("a")).thenReturn(runtime);

        var result = service.scan(now);

        assertEquals(1, result.getRemovedDevices().size());
        assertEquals(1, device.getDelete());
        assertEquals("REMOVED", runtime.getState());
        verify(service.taskRecoveryService).recoverDeviceOffline("a", now);
    }

    @Test
    void removalNoticeOnlyReportsImportantDevicesWhileRemovingAllExpiredDevices() {
        var service = new DeviceRuntimeService();
        service.runtimeMapper = mock(DeviceRuntimeMapper.class);
        service.deviceMapper = mock(DeviceMapper.class);
        service.dynamicInfo = new DynamicInfo();
        service.taskRecoveryService = mock(TaskRecoveryService.class);
        service.messageService = mock(MessageServiceImpl.class);
        service.recoveryExecutor = Runnable::run;
        var now = LocalDateTime.of(2026, 7, 19, 12, 0);
        var deviceA = new DeviceEntity().setDeviceName("A").setDeviceToken("a").setDelete(0);
        var deviceB = new DeviceEntity().setDeviceName("B").setDeviceToken("b").setDelete(0);
        when(service.deviceMapper.selectList(any())).thenReturn(List.of(deviceA, deviceB));
        when(service.deviceMapper.updateById(any())).thenReturn(1);
        when(service.runtimeMapper.selectById("a")).thenReturn(new DeviceRuntimeEntity()
                .setDeviceToken("a").setState("OFFLINE")
                .setOfflineSince(now.minusHours(24)).setLastNoticeLevel(60));
        when(service.runtimeMapper.selectById("b")).thenReturn(new DeviceRuntimeEntity()
                .setDeviceToken("b").setState("OFFLINE")
                .setOfflineSince(now.minusHours(24)).setLastNoticeLevel(60));

        var result = service.scan(now);

        assertEquals(2, result.getRemovedDevices().size());
        assertEquals(1, deviceA.getDelete());
        assertEquals(1, deviceB.getDelete());
        verify(service.messageService).pushAdmin(eq("[审判庭重点设备] 设备移除"),
                org.mockito.ArgumentMatchers.argThat(content -> content.contains("A")
                        && !content.contains("B")));
    }

    @Test
    void thirdConsecutiveFailureSuspendsForOneHourAndNotifiesOnce() {
        var service = new DeviceRuntimeService();
        service.runtimeMapper = mock(DeviceRuntimeMapper.class);
        service.deviceMapper = mock(DeviceMapper.class);
        service.dynamicInfo = new DynamicInfo();
        service.messageService = mock(MessageServiceImpl.class);
        var runtime = new DeviceRuntimeEntity().setDeviceToken("device-1")
                .setConsecutiveFailures(2).setLastFailureNoticeCount(0);
        var device = new DeviceEntity().setDeviceToken("device-1").setDeviceName("A");
        when(service.runtimeMapper.selectById("device-1")).thenReturn(runtime);
        when(service.deviceMapper.selectOne(any())).thenReturn(device);
        var now = LocalDateTime.of(2026, 7, 19, 13, 0);

        assertTrue(service.recordTaskFailure("device-1", now));
        assertTrue(!service.recordTaskFailure("device-1", now.plusMinutes(1)));

        assertEquals(now.plusHours(1), runtime.getSuspendedUntil());
        verify(service.messageService, times(1)).pushAdmin(contains("[审判庭重点设备] 设备异常"), contains("A"));
    }

    @Test
    void nonImportantDeviceFailureStillSuspendsButDoesNotNotifyAdmin() {
        var service = new DeviceRuntimeService();
        service.runtimeMapper = mock(DeviceRuntimeMapper.class);
        service.deviceMapper = mock(DeviceMapper.class);
        service.dynamicInfo = new DynamicInfo();
        service.messageService = mock(MessageServiceImpl.class);
        var runtime = new DeviceRuntimeEntity().setDeviceToken("device-b")
                .setConsecutiveFailures(2).setLastFailureNoticeCount(0);
        var device = new DeviceEntity().setDeviceToken("device-b").setDeviceName("B");
        when(service.runtimeMapper.selectById("device-b")).thenReturn(runtime);
        when(service.deviceMapper.selectOne(any())).thenReturn(device);
        var now = LocalDateTime.of(2026, 7, 19, 13, 0);

        assertTrue(service.recordTaskFailure("device-b", now));

        assertEquals(now.plusHours(1), runtime.getSuspendedUntil());
        verify(service.messageService, never()).pushAdmin(any(), any());
    }

    private static final class LockCheckingHaltList extends ArrayList<String> {
        @Override
        public boolean contains(Object value) {
            assertTrue(Thread.holdsLock(this), "haltList contains must hold the list monitor");
            return super.contains(value);
        }
    }
}
