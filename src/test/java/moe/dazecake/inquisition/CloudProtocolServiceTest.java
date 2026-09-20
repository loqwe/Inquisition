package moe.dazecake.inquisition;

import moe.dazecake.inquisition.model.dto.cloud.*;
import moe.dazecake.inquisition.service.impl.CloudProtocolService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CloudProtocolServiceTest {
    @Test void registerDoesNotExposeTokenAndHeartbeatAuthenticatesHash() {
        CloudProtocolService service = new CloudProtocolService();
        CloudRegisterDTO r = new CloudRegisterDTO(); r.setDeviceToken("secret");
        assertFalse(service.register(r).getData().containsValue("secret"));
        CloudHeartbeatDTO h = new CloudHeartbeatDTO(); h.setDeviceToken("secret");
        assertEquals(200, service.heartbeat(h).getCode());
        h.setDeviceToken("bad"); assertNotEquals(200, service.heartbeat(h).getCode());
    }

    @Test void reportsAreIdempotentAndRequireAttemptAndLease() {
        CloudProtocolService service = new CloudProtocolService();
        CloudRegisterDTO r = new CloudRegisterDTO(); r.setDeviceToken("t"); service.register(r);
        CloudProtocolService.CloudTask task = service.enqueue("task-1", 7L, "{}");
        assertEquals(200, service.getTask("t").getCode());
        CloudTaskReportDTO report = new CloudTaskReportDTO(); report.setDeviceToken("t"); report.setTaskId(task.taskId); report.setAttemptId(task.attemptId); report.setLeaseId(task.leaseId);
        assertEquals(200, service.complete(report).getCode());
        assertEquals(200, service.complete(report).getCode());
        report.setLeaseId("forged"); assertNotEquals(200, service.fail(report).getCode());
    }

    @Test void databaseFailuresDoNotSilentlyFallBackToMemoryQueue() {
        CloudProtocolService service = new CloudProtocolService();
        CloudRegisterDTO register = new CloudRegisterDTO();
        register.setDeviceToken("t");
        service.register(register);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString())).thenThrow(new DataAccessResourceFailureException("db down"));
        ReflectionTestUtils.setField(service, "jdbc", jdbc);

        assertThrows(DataAccessResourceFailureException.class, () -> service.getTask("t"));
    }
}
