package moe.dazecake.inquisition.service.impl;

import java.time.LocalDateTime;

public class SklandCalibrationResult {
    private final int sanity;
    private final int maxSanity;
    private final LocalDateTime lastOnlineAt;
    private final LocalDateTime observedAt;

    public SklandCalibrationResult(int sanity, int maxSanity, LocalDateTime lastOnlineAt,
                                   LocalDateTime observedAt) {
        this.sanity = sanity;
        this.maxSanity = maxSanity;
        this.lastOnlineAt = lastOnlineAt;
        this.observedAt = observedAt;
    }

    public int getSanity() {
        return sanity;
    }

    public int getMaxSanity() {
        return maxSanity;
    }

    public LocalDateTime getLastOnlineAt() {
        return lastOnlineAt;
    }

    public LocalDateTime getObservedAt() {
        return observedAt;
    }
}
