package moe.dazecake.inquisition.model.vo.task;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class ScheduledTaskStatusVO {
    private final String key;
    private final String name;
    private final String description;
    private final String cron;
    private final String timeZone;
    private final String scheduleText;
    private final String status;
    private final boolean enabled;
    private final String lastOutcome;
    private final String lastTriggerSource;
    private final LocalDateTime lastStartedAt;
    private final LocalDateTime lastFinishedAt;
    private final LocalDateTime lastSuccessAt;
    private final LocalDateTime lastFailureAt;
    private final LocalDateTime nextRunAt;
    private final Long lastDurationMs;
    private final int consecutiveFailures;
    private final long runCount;
    private final String lastError;
    private final LocalDateTime updatedAt;
}
