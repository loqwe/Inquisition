package moe.dazecake.inquisition.service.impl;

import com.google.gson.JsonObject;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

public class SklandPlayerStatus {
    private final int currentSanity;
    private final int maxSanity;
    private final long completeRecoveryTime;
    private final LocalDateTime lastOnlineAt;

    public SklandPlayerStatus(int currentSanity, int maxSanity, long completeRecoveryTime,
                              LocalDateTime lastOnlineAt) {
        this.currentSanity = currentSanity;
        this.maxSanity = maxSanity;
        this.completeRecoveryTime = completeRecoveryTime;
        this.lastOnlineAt = lastOnlineAt;
    }

    public static SklandPlayerStatus fromJson(JsonObject root) {
        var data = root.getAsJsonObject("data");
        var status = data.getAsJsonObject("status");
        var ap = status.getAsJsonObject("ap");
        var lastOnlineTs = status.has("lastOnlineTs") && !status.get("lastOnlineTs").isJsonNull()
                ? status.get("lastOnlineTs").getAsLong() : 0L;
        var lastOnlineAt = lastOnlineTs <= 0 ? null
                : LocalDateTime.ofInstant(Instant.ofEpochSecond(lastOnlineTs), ZoneId.of("Asia/Shanghai"));
        return new SklandPlayerStatus(
                ap.get("current").getAsInt(),
                ap.get("max").getAsInt(),
                ap.has("completeRecoveryTime") ? ap.get("completeRecoveryTime").getAsLong() : 0L,
                lastOnlineAt);
    }

    public int getCurrentSanity() {
        return currentSanity;
    }

    public int getMaxSanity() {
        return maxSanity;
    }

    public long getCompleteRecoveryTime() {
        return completeRecoveryTime;
    }

    public LocalDateTime getLastOnlineAt() {
        return lastOnlineAt;
    }

}
