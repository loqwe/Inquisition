package moe.dazecake.inquisition.service.impl;

public class PartialScheduledDispatchException extends IllegalStateException {
    private final int failureCount;

    public PartialScheduledDispatchException(int failureCount) {
        super("Scheduled account dispatch completed with " + failureCount + " failure(s)");
        this.failureCount = failureCount;
    }

    public int getFailureCount() {
        return failureCount;
    }
}
