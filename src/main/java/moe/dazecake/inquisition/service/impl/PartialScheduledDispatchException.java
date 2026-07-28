package moe.dazecake.inquisition.service.impl;

import moe.dazecake.inquisition.model.entity.AccountScheduledRunEntity;

import java.util.List;
import java.util.Objects;

public class PartialScheduledDispatchException extends IllegalStateException {
    private final int failureCount;
    private final List<AccountScheduledRunEntity> dispatchableRuns;

    public PartialScheduledDispatchException(
            int failureCount, List<AccountScheduledRunEntity> dispatchableRuns) {
        super("Scheduled account dispatch completed with " + failureCount + " failure(s)");
        this.failureCount = failureCount;
        this.dispatchableRuns = List.copyOf(
                Objects.requireNonNull(dispatchableRuns, "dispatchableRuns"));
    }

    public int getFailureCount() {
        return failureCount;
    }

    public List<AccountScheduledRunEntity> getDispatchableRuns() {
        return dispatchableRuns;
    }
}
