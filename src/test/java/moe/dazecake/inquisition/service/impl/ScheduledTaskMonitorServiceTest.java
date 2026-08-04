package moe.dazecake.inquisition.service.impl;

import moe.dazecake.inquisition.mapper.ScheduledTaskRuntimeMapper;
import moe.dazecake.inquisition.model.entity.ScheduledTaskRuntimeEntity;
import moe.dazecake.inquisition.model.local.ScheduledTaskDefinition;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduledTaskMonitorServiceTest {

    @Test
    void derivesHealthForEveryRuntimeStateAndKeepsDefinitionOrder() {
        var service = new ScheduledTaskMonitorService();
        service.runtimeMapper = mock(ScheduledTaskRuntimeMapper.class);
        var now = LocalDateTime.of(2026, 7, 27, 15, 0);
        service.register(definition(1, "healthy", true, Duration.ofMinutes(2), Duration.ofMinutes(2)));
        service.register(definition(2, "running", true, Duration.ofMinutes(5), Duration.ofMinutes(2)));
        service.register(definition(3, "stalled", true, Duration.ofMinutes(5), Duration.ofMinutes(2)));
        service.register(definition(4, "failed", true, Duration.ofMinutes(2), Duration.ofMinutes(2)));
        service.register(definition(5, "missed", true, Duration.ofMinutes(2), Duration.ofMinutes(2)));
        service.register(definition(6, "waiting", true, Duration.ofMinutes(2), Duration.ofMinutes(2)));
        service.register(definition(7, "disabled", false, Duration.ofMinutes(2), Duration.ofMinutes(2)));
        when(service.runtimeMapper.selectList(any())).thenReturn(List.of(
                runtime("healthy").setLastOutcome("SUCCESS").setLastSuccessAt(now.minusMinutes(1))
                        .setNextRunAt(now.plusMinutes(1)).setRunning(0),
                runtime("running").setLastOutcome("RUNNING").setLastStartedAt(now.minusMinutes(2))
                        .setNextRunAt(now.plusMinutes(1)).setRunning(1),
                runtime("stalled").setLastOutcome("RUNNING").setLastStartedAt(now.minusMinutes(6))
                        .setNextRunAt(now.minusMinutes(1)).setRunning(1),
                runtime("failed").setLastOutcome("FAILED").setLastFailureAt(now.minusMinutes(1))
                        .setNextRunAt(now.plusMinutes(1)).setRunning(0),
                runtime("missed").setLastOutcome("SUCCESS").setLastSuccessAt(now.minusHours(1))
                        .setNextRunAt(now.minusMinutes(3)).setRunning(0)
        ));

        var overview = service.getOverview(now);

        assertEquals(List.of("healthy", "running", "stalled", "failed", "missed", "waiting", "disabled"),
                overview.getTasks().stream().map(task -> task.getKey()).collect(Collectors.toList()));
        assertEquals(List.of("HEALTHY", "RUNNING", "STALLED", "FAILED", "MISSED", "WAITING", "DISABLED"),
                overview.getTasks().stream().map(task -> task.getStatus()).collect(Collectors.toList()));
        assertEquals(7, overview.getTotalCount());
        assertEquals(1, overview.getHealthyCount());
        assertEquals(1, overview.getRunningCount());
        assertEquals(3, overview.getAbnormalCount());
        assertEquals(1, overview.getWaitingCount());
        assertEquals(1, overview.getDisabledCount());
    }

    @Test
    void recordsFailureDurationAndSanitizesTheErrorSummary() {
        var service = new ScheduledTaskMonitorService();
        service.runtimeMapper = mock(ScheduledTaskRuntimeMapper.class);
        var startedAt = LocalDateTime.of(2026, 7, 27, 15, 0);
        var finishedAt = startedAt.plusSeconds(3);
        var clockValues = new ArrayDeque<>(List.of(startedAt, finishedAt));
        service.clock = clockValues::removeFirst;
        var runtime = runtime("failing").setRunning(0).setRunCount(2L).setConsecutiveFailures(1);
        when(service.runtimeMapper.selectById("failing")).thenReturn(runtime);

        var error = assertThrows(IllegalStateException.class,
                () -> service.execute("failing", "CRON", () -> {
                    throw new IllegalStateException("first line\nTOKEN=secret-value");
                }));

        assertEquals("first line\nTOKEN=secret-value", error.getMessage());
        var captor = ArgumentCaptor.forClass(ScheduledTaskRuntimeEntity.class);
        verify(service.runtimeMapper, org.mockito.Mockito.atLeastOnce()).updateById(captor.capture());
        var saved = captor.getValue();
        assertEquals("FAILED", saved.getLastOutcome());
        assertEquals(0, saved.getRunning());
        assertEquals(3000L, saved.getLastDurationMs());
        assertEquals(2, saved.getConsecutiveFailures());
        assertEquals(3L, saved.getRunCount());
        assertTrue(saved.getLastError().startsWith("IllegalStateException: first line TOKEN=<redacted>"));
        assertTrue(saved.getLastError().length() <= 1000);
    }

    @Test
    void rejectsDuplicateTaskKeys() {
        var service = new ScheduledTaskMonitorService();
        service.runtimeMapper = mock(ScheduledTaskRuntimeMapper.class);
        service.register(definition(1, "duplicate", true, Duration.ofMinutes(1), Duration.ofMinutes(1)));

        assertThrows(IllegalArgumentException.class,
                () -> service.register(definition(2, "duplicate", true,
                        Duration.ofMinutes(2), Duration.ofMinutes(2))));
    }

    @Test
    void preservesAMissedExecutionWhenSchedulingTheNextRunAfterRestart() {
        var service = new ScheduledTaskMonitorService();
        service.runtimeMapper = mock(ScheduledTaskRuntimeMapper.class);
        service.register(definition(1, "restart-gap", true,
                Duration.ofMinutes(2), Duration.ofMinutes(2)));
        var now = LocalDateTime.of(2026, 7, 27, 15, 5);
        var runtime = runtime("restart-gap")
                .setLastOutcome("SUCCESS")
                .setLastStartedAt(LocalDateTime.of(2026, 7, 27, 13, 0))
                .setLastSuccessAt(LocalDateTime.of(2026, 7, 27, 13, 0))
                .setNextRunAt(LocalDateTime.of(2026, 7, 27, 15, 0));
        when(service.runtimeMapper.selectById("restart-gap")).thenReturn(runtime);

        service.recordNextRun("restart-gap", LocalDateTime.of(2026, 7, 27, 16, 0), now);

        assertEquals("MISSED", runtime.getLastOutcome());
        assertEquals(now, runtime.getLastFailureAt());
        assertEquals(1, runtime.getConsecutiveFailures());
        assertEquals(LocalDateTime.of(2026, 7, 27, 16, 0), runtime.getNextRunAt());
        verify(service.runtimeMapper).updateById(runtime);
    }

    @Test
    void keepsExplicitMissedStateUntilTheNextSuccessfulExecution() {
        var service = new ScheduledTaskMonitorService();
        service.runtimeMapper = mock(ScheduledTaskRuntimeMapper.class);
        service.register(definition(1, "restart-gap", true,
                Duration.ofMinutes(2), Duration.ofMinutes(2)));
        var now = LocalDateTime.of(2026, 7, 27, 15, 5);
        var runtime = runtime("restart-gap")
                .setLastOutcome("MISSED")
                .setLastStartedAt(LocalDateTime.of(2026, 7, 27, 13, 0))
                .setLastSuccessAt(LocalDateTime.of(2026, 7, 27, 13, 0))
                .setLastFailureAt(now)
                .setNextRunAt(LocalDateTime.of(2026, 7, 27, 16, 0));
        when(service.runtimeMapper.selectList(any())).thenReturn(List.of(runtime));
        when(service.runtimeMapper.selectById("restart-gap")).thenReturn(runtime);

        assertEquals("MISSED", service.getOverview(now).getTasks().get(0).getStatus());

        var executionStartedAt = now.plusMinutes(1);
        var executionFinishedAt = executionStartedAt.plusSeconds(1);
        var clockValues = new ArrayDeque<>(List.of(executionStartedAt, executionFinishedAt));
        service.clock = clockValues::removeFirst;
        service.execute("restart-gap", "CRON", () -> { });

        assertEquals("HEALTHY",
                service.getOverview(executionFinishedAt).getTasks().get(0).getStatus());
    }

    @Test
    void monitoringStorageFailureNeverPreventsTheRealTaskFromRunning() {
        var service = new ScheduledTaskMonitorService();
        service.runtimeMapper = mock(ScheduledTaskRuntimeMapper.class);
        when(service.runtimeMapper.selectById("resilient"))
                .thenThrow(new IllegalStateException("monitor database unavailable"));
        var executed = new AtomicBoolean();

        service.execute("resilient", "CRON", () -> executed.set(true));

        assertTrue(executed.get());
    }

    private static ScheduledTaskDefinition definition(int order, String key, boolean enabled,
                                                      Duration maxRunDuration, Duration lateTolerance) {
        return ScheduledTaskDefinition.builder()
                .order(order)
                .key(key)
                .name("任务 " + key)
                .description("description")
                .cron("0 * * * * ?")
                .timeZone("Asia/Shanghai")
                .scheduleText("每分钟")
                .maxRunDuration(maxRunDuration)
                .lateTolerance(lateTolerance)
                .enabledSupplier(() -> enabled)
                .build();
    }

    private static ScheduledTaskRuntimeEntity runtime(String key) {
        return new ScheduledTaskRuntimeEntity()
                .setTaskKey(key)
                .setRunning(0)
                .setRunCount(0L)
                .setConsecutiveFailures(0);
    }
}
