package moe.dazecake.inquisition.service.impl;

import moe.dazecake.inquisition.model.dto.heartbeat.HeartBeatDTO;
import moe.dazecake.inquisition.model.entity.TaskAssignmentEntity;
import moe.dazecake.inquisition.utils.DynamicInfo;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HeartBeatServiceImplTest {

    @Test
    void legacyHeartbeatWithoutAssignmentMetadataIsAccepted() {
        var service = new HeartBeatServiceImpl();
        service.dynamicInfo = new DynamicInfo();
        service.deviceRuntimeService = mock(DeviceRuntimeService.class);
        service.taskAssignmentService = mock(TaskAssignmentService.class);
        var assignment = new TaskAssignmentEntity()
                .setAssignmentId("assignment-current")
                .setDeviceToken("device-1");
        when(service.taskAssignmentService.findByDevice("device-1")).thenReturn(Optional.of(assignment));
        when(service.deviceRuntimeService.recordHeartbeat(any(), any(), any(), any(), any())).thenReturn(false);

        var result = service.postHeartBeat(new HeartBeatDTO(1001, "device-1", null, null));

        assertEquals(200, result.getCode());
    }

    @Test
    void matchingAssignmentHeartbeatIsAcceptedAndUpdatesRuntime() {
        var service = new HeartBeatServiceImpl();
        service.dynamicInfo = new DynamicInfo();
        service.deviceRuntimeService = mock(DeviceRuntimeService.class);
        service.taskAssignmentService = mock(TaskAssignmentService.class);
        var assignment = new TaskAssignmentEntity()
                .setAssignmentId("assignment-current")
                .setDeviceToken("device-1");
        when(service.taskAssignmentService.findByDevice("device-1")).thenReturn(Optional.of(assignment));
        when(service.taskAssignmentService.matchesSubmission(assignment, "device-1", "assignment-current"))
                .thenReturn(true);
        when(service.deviceRuntimeService.recordHeartbeat(any(), any(), any(), any(), any())).thenReturn(false);

        var result = service.postHeartBeat(new HeartBeatDTO(1001, "device-1", "assignment-current", "1.0"));

        assertEquals(200, result.getCode());
    }

    @Test
    void staleAssignmentHeartbeatRequestsClientShutdown() {
        var service = new HeartBeatServiceImpl();
        service.dynamicInfo = new DynamicInfo();
        service.deviceRuntimeService = mock(DeviceRuntimeService.class);
        service.taskAssignmentService = mock(TaskAssignmentService.class);
        var assignment = new TaskAssignmentEntity()
                .setAssignmentId("assignment-current")
                .setDeviceToken("device-1");
        when(service.taskAssignmentService.findByDevice("device-1")).thenReturn(Optional.of(assignment));
        when(service.taskAssignmentService.matchesSubmission(assignment, "device-1", "assignment-old"))
                .thenReturn(false);

        var result = service.postHeartBeat(new HeartBeatDTO(1001, "device-1", "assignment-old", "1.0"));

        assertEquals(500, result.getCode());
        assertEquals(1, service.dynamicInfo.getHaltList().stream()
                .filter("device-1"::equals).count());
    }

    @Test
    void legacyHaltAcknowledgementIsIdempotent() {
        var service = new HeartBeatServiceImpl();
        service.dynamicInfo = new DynamicInfo();
        service.dynamicInfo.getHaltList().add("device-1");
        service.dynamicInfo.getHaltList().add("device-1");
        var heartbeat = new HeartBeatDTO(1, "device-1", null, null);

        assertEquals(200, service.postHaltComplete(heartbeat).getCode());
        assertEquals(200, service.postHaltComplete(heartbeat).getCode());
        assertTrue(!service.dynamicInfo.getHaltList().contains("device-1"));
    }

    @Test
    void heartbeatHaltCheckUsesHaltListLock() {
        var service = new HeartBeatServiceImpl();
        service.dynamicInfo = new DynamicInfo();
        var haltList = new LockCheckingHaltList();
        haltList.add("device-1");
        service.dynamicInfo.setHaltList(haltList);
        service.deviceRuntimeService = mock(DeviceRuntimeService.class);
        service.taskAssignmentService = mock(TaskAssignmentService.class);
        when(service.deviceRuntimeService.recordHeartbeat(any(), any(), any(), any(), any())).thenReturn(false);
        when(service.taskAssignmentService.findByDevice("device-1")).thenReturn(Optional.empty());

        var result = service.postHeartBeat(new HeartBeatDTO(1001, "device-1", null, null));

        assertEquals(500, result.getCode());
    }

    @Test
    void haltAcknowledgementUsesHaltListLock() {
        var service = new HeartBeatServiceImpl();
        service.dynamicInfo = new DynamicInfo();
        var haltList = new LockCheckingHaltList();
        haltList.add("device-1");
        haltList.add("device-1");
        service.dynamicInfo.setHaltList(haltList);

        var result = service.postHaltComplete(new HeartBeatDTO(1, "device-1", null, null));

        assertEquals(200, result.getCode());
        assertTrue(haltList.isEmpty());
    }

    private static final class LockCheckingHaltList extends ArrayList<String> {
        @Override
        public boolean contains(Object value) {
            assertTrue(Thread.holdsLock(this), "haltList contains must hold the list monitor");
            return super.contains(value);
        }

        @Override
        public boolean remove(Object value) {
            assertTrue(Thread.holdsLock(this), "haltList remove must hold the list monitor");
            return super.remove(value);
        }
    }
}
