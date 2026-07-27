package moe.dazecake.inquisition.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import moe.dazecake.inquisition.mapper.ScheduledTaskRuntimeMapper;
import moe.dazecake.inquisition.model.entity.ScheduledTaskRuntimeEntity;
import moe.dazecake.inquisition.model.local.ScheduledTaskDefinition;
import moe.dazecake.inquisition.model.vo.task.ScheduledTaskOverviewVO;
import moe.dazecake.inquisition.model.vo.task.ScheduledTaskStatusVO;
import moe.dazecake.inquisition.utils.GameDayClock;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Service
@Slf4j
public class ScheduledTaskMonitorService {
    private static final int MAX_ERROR_LENGTH = 1000;

    @Resource
    ScheduledTaskRuntimeMapper runtimeMapper;

    final Map<String, ScheduledTaskDefinition> definitions = new ConcurrentHashMap<>();
    Supplier<LocalDateTime> clock = GameDayClock::now;

    public void register(ScheduledTaskDefinition definition) {
        if (definition == null || definition.getKey() == null || definition.getKey().isBlank()) {
            throw new IllegalArgumentException("scheduled task key is required");
        }
        if (definitions.putIfAbsent(definition.getKey(), definition) != null) {
            throw new IllegalArgumentException("duplicate scheduled task key: " + definition.getKey());
        }
    }

    public void execute(String taskKey, String triggerSource, Runnable task) {
        var startedAt = clock.get();
        recordSafely(taskKey, "start", () -> markStarted(taskKey, triggerSource, startedAt));
        try {
            task.run();
            recordSafely(taskKey, "success", () -> markSucceeded(taskKey, clock.get()));
        } catch (RuntimeException | Error throwable) {
            recordSafely(taskKey, "failure", () -> markFailed(taskKey, throwable, clock.get()));
            throw throwable;
        }
    }

    public void recordNextRun(String taskKey, LocalDateTime nextRunAt, LocalDateTime requestedNow) {
        var now = requestedNow == null ? clock.get() : requestedNow;
        var definition = definitions.get(taskKey);
        mutate(taskKey, runtime -> {
            var previousNextRun = runtime.getNextRunAt();
            if (definition != null && previousNextRun != null
                    && previousNextRun.plus(definition.getLateTolerance()).isBefore(now)
                    && (runtime.getLastStartedAt() == null || runtime.getLastStartedAt().isBefore(previousNextRun))) {
                runtime.setRunning(0)
                        .setLastOutcome("MISSED")
                        .setLastFailureAt(now)
                        .setConsecutiveFailures(value(runtime.getConsecutiveFailures()) + 1);
            }
            runtime.setNextRunAt(nextRunAt).setUpdatedAt(now);
        });
    }

    public ScheduledTaskOverviewVO getOverview(LocalDateTime requestedNow) {
        var now = requestedNow == null ? clock.get() : requestedNow;
        var orderedDefinitions = new ArrayList<>(definitions.values());
        orderedDefinitions.sort(Comparator.comparingInt(ScheduledTaskDefinition::getOrder));

        Map<String, ScheduledTaskRuntimeEntity> runtimeByKey = new HashMap<>();
        if (!orderedDefinitions.isEmpty()) {
            var keys = new ArrayList<String>();
            orderedDefinitions.forEach(definition -> keys.add(definition.getKey()));
            var runtimes = runtimeMapper.selectList(Wrappers.<ScheduledTaskRuntimeEntity>lambdaQuery()
                    .in(ScheduledTaskRuntimeEntity::getTaskKey, keys));
            if (runtimes != null) {
                runtimes.forEach(runtime -> runtimeByKey.put(runtime.getTaskKey(), runtime));
            }
        }

        var tasks = new ArrayList<ScheduledTaskStatusVO>();
        for (var definition : orderedDefinitions) {
            var runtime = runtimeByKey.get(definition.getKey());
            var status = deriveStatus(definition, runtime, now);
            tasks.add(toStatus(definition, runtime, status));
        }

        var healthy = countStatus(tasks, "HEALTHY");
        var running = countStatus(tasks, "RUNNING");
        var abnormal = countStatus(tasks, "FAILED") + countStatus(tasks, "MISSED")
                + countStatus(tasks, "STALLED");
        var waiting = countStatus(tasks, "WAITING");
        var disabled = countStatus(tasks, "DISABLED");
        return new ScheduledTaskOverviewVO(now, tasks.size(), healthy, running, abnormal, waiting, disabled, tasks);
    }

    private void markStarted(String taskKey, String triggerSource, LocalDateTime now) {
        mutate(taskKey, runtime -> runtime
                .setRunning(1)
                .setLastOutcome("RUNNING")
                .setLastTriggerSource(triggerSource)
                .setLastStartedAt(now)
                .setRunCount(value(runtime.getRunCount()) + 1)
                .setUpdatedAt(now));
    }

    private void markSucceeded(String taskKey, LocalDateTime now) {
        mutate(taskKey, runtime -> runtime
                .setRunning(0)
                .setLastOutcome("SUCCESS")
                .setLastFinishedAt(now)
                .setLastSuccessAt(now)
                .setLastDurationMs(durationMillis(runtime.getLastStartedAt(), now))
                .setConsecutiveFailures(0)
                .setLastError(null)
                .setUpdatedAt(now));
    }

    private void markFailed(String taskKey, Throwable throwable, LocalDateTime now) {
        mutate(taskKey, runtime -> runtime
                .setRunning(0)
                .setLastOutcome("FAILED")
                .setLastFinishedAt(now)
                .setLastFailureAt(now)
                .setLastDurationMs(durationMillis(runtime.getLastStartedAt(), now))
                .setConsecutiveFailures(value(runtime.getConsecutiveFailures()) + 1)
                .setLastError(sanitizeError(throwable))
                .setUpdatedAt(now));
    }

    private void mutate(String taskKey, java.util.function.Consumer<ScheduledTaskRuntimeEntity> mutator) {
        synchronized (taskKey.intern()) {
            var runtime = runtimeMapper.selectById(taskKey);
            var exists = runtime != null;
            if (runtime == null) {
                runtime = new ScheduledTaskRuntimeEntity().setTaskKey(taskKey);
            }
            mutator.accept(runtime);
            if (exists) {
                runtimeMapper.updateById(runtime);
                return;
            }
            try {
                runtimeMapper.insert(runtime);
            } catch (DuplicateKeyException duplicateKeyException) {
                runtimeMapper.updateById(runtime);
            }
        }
    }

    private void recordSafely(String taskKey, String stage, Runnable recorder) {
        try {
            recorder.run();
        } catch (RuntimeException exception) {
            log.warn("脚本任务监控记录失败: task={}, stage={}", taskKey, stage, exception);
        }
    }

    private String deriveStatus(ScheduledTaskDefinition definition, ScheduledTaskRuntimeEntity runtime,
                                LocalDateTime now) {
        if (!definition.isEnabled()) {
            return "DISABLED";
        }
        if (runtime == null) {
            return "WAITING";
        }
        if (Integer.valueOf(1).equals(runtime.getRunning())) {
            if (runtime.getLastStartedAt() != null
                    && runtime.getLastStartedAt().plus(definition.getMaxRunDuration()).isBefore(now)) {
                return "STALLED";
            }
            return "RUNNING";
        }
        if ("FAILED".equals(runtime.getLastOutcome())) {
            return "FAILED";
        }
        if ("MISSED".equals(runtime.getLastOutcome())) {
            return "MISSED";
        }
        if (runtime.getNextRunAt() != null
                && runtime.getNextRunAt().plus(definition.getLateTolerance()).isBefore(now)) {
            return "MISSED";
        }
        if (runtime.getLastSuccessAt() != null) {
            return "HEALTHY";
        }
        return "WAITING";
    }

    private ScheduledTaskStatusVO toStatus(ScheduledTaskDefinition definition,
                                           ScheduledTaskRuntimeEntity runtime, String status) {
        return new ScheduledTaskStatusVO(
                definition.getKey(), definition.getName(), definition.getDescription(), definition.getCron(),
                definition.getTimeZone(), definition.getScheduleText(), status, definition.isEnabled(),
                runtime == null ? null : runtime.getLastOutcome(),
                runtime == null ? null : runtime.getLastTriggerSource(),
                runtime == null ? null : runtime.getLastStartedAt(),
                runtime == null ? null : runtime.getLastFinishedAt(),
                runtime == null ? null : runtime.getLastSuccessAt(),
                runtime == null ? null : runtime.getLastFailureAt(),
                runtime == null ? null : runtime.getNextRunAt(),
                runtime == null ? null : runtime.getLastDurationMs(),
                runtime == null ? 0 : value(runtime.getConsecutiveFailures()),
                runtime == null ? 0 : value(runtime.getRunCount()),
                runtime == null ? null : runtime.getLastError(),
                runtime == null ? null : runtime.getUpdatedAt());
    }

    private int countStatus(ArrayList<ScheduledTaskStatusVO> tasks, String status) {
        return (int) tasks.stream().filter(task -> status.equals(task.getStatus())).count();
    }

    private long durationMillis(LocalDateTime startedAt, LocalDateTime finishedAt) {
        return startedAt == null ? 0 : Math.max(0, Duration.between(startedAt, finishedAt).toMillis());
    }

    private String sanitizeError(Throwable throwable) {
        var message = throwable.getMessage() == null ? "" : throwable.getMessage();
        message = message.replaceAll("(?i)(token|password|secret|authorization|cookie)\\s*=\\s*\\S+",
                "$1=<redacted>");
        var summary = (throwable.getClass().getSimpleName() + ": " + message)
                .replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();
        return summary.length() <= MAX_ERROR_LENGTH ? summary : summary.substring(0, MAX_ERROR_LENGTH);
    }

    private int value(Integer value) {
        return value == null ? 0 : value;
    }

    private long value(Long value) {
        return value == null ? 0 : value;
    }
}
