package moe.dazecake.inquisition.utils;

public final class RetryBackoff {
    private static final int[] MINUTES = {2, 5, 15, 30, 60};

    private RetryBackoff() {
    }

    public static int delayMinutes(int retryCount) {
        if (retryCount <= 0) {
            return MINUTES[0];
        }
        return MINUTES[Math.min(retryCount - 1, MINUTES.length - 1)];
    }
}
