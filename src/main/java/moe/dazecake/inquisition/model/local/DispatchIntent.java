package moe.dazecake.inquisition.model.local;

import java.time.LocalDateTime;

public final class DispatchIntent {
    public static final String SOURCE_AUTO = "AUTO";
    public static final String SOURCE_MANUAL = "MANUAL";
    public static final String SOURCE_SCHEDULED = "SCHEDULED";
    public static final String SOURCE_URGENT_26 = "URGENT_26";

    public static final int PRIORITY_AUTO = 100;
    public static final int PRIORITY_HIGH = 200;
    public static final int PRIORITY_URGENT_26 = 300;

    private final Long accountId;
    private final String source;
    private final int priority;
    private final Long scheduledRunId;
    private final Long urgentTaskId;
    private final LocalDateTime enqueuedAt;

    private DispatchIntent(Long accountId, String source, int priority,
                           Long scheduledRunId, Long urgentTaskId,
                           LocalDateTime enqueuedAt) {
        this.accountId = accountId;
        this.source = source;
        this.priority = priority;
        this.scheduledRunId = scheduledRunId;
        this.urgentTaskId = urgentTaskId;
        this.enqueuedAt = enqueuedAt;
    }

    public static DispatchIntent auto(Long accountId, LocalDateTime enqueuedAt) {
        return new DispatchIntent(accountId, SOURCE_AUTO, PRIORITY_AUTO,
                null, null, enqueuedAt);
    }

    public static DispatchIntent manual(Long accountId, LocalDateTime enqueuedAt) {
        return new DispatchIntent(accountId, SOURCE_MANUAL, PRIORITY_HIGH,
                null, null, enqueuedAt);
    }

    public static DispatchIntent scheduled(Long accountId, Long scheduledRunId,
                                           LocalDateTime enqueuedAt) {
        return new DispatchIntent(accountId, SOURCE_SCHEDULED, PRIORITY_HIGH,
                scheduledRunId, null, enqueuedAt);
    }

    public static DispatchIntent urgent(Long accountId, Long urgentTaskId,
                                        LocalDateTime enqueuedAt) {
        return new DispatchIntent(accountId, SOURCE_URGENT_26, PRIORITY_URGENT_26,
                null, urgentTaskId, enqueuedAt);
    }

    public Long getAccountId() {
        return accountId;
    }

    public String getSource() {
        return source;
    }

    public int getPriority() {
        return priority;
    }

    public Long getScheduledRunId() {
        return scheduledRunId;
    }

    public Long getUrgentTaskId() {
        return urgentTaskId;
    }

    public LocalDateTime getEnqueuedAt() {
        return enqueuedAt;
    }
}
