package moe.dazecake.inquisition.model.local;

import lombok.Builder;
import lombok.Getter;

import java.time.Duration;
import java.util.function.BooleanSupplier;

@Getter
@Builder
public class ScheduledTaskDefinition {
    private final int order;
    private final String key;
    private final String name;
    private final String description;
    private final String cron;
    private final String timeZone;
    private final String scheduleText;
    private final Duration maxRunDuration;
    private final Duration lateTolerance;
    private final BooleanSupplier enabledSupplier;

    public boolean isEnabled() {
        return enabledSupplier == null || enabledSupplier.getAsBoolean();
    }
}
