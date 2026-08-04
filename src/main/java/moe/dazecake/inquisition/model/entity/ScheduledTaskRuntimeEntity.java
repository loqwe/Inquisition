package moe.dazecake.inquisition.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Accessors(chain = true)
@TableName("scheduled_task_runtime")
public class ScheduledTaskRuntimeEntity {
    @TableId(type = IdType.INPUT)
    private String taskKey;
    private Integer running = 0;
    private String lastOutcome;
    private String lastTriggerSource;
    private LocalDateTime lastStartedAt;
    private LocalDateTime lastFinishedAt;
    private LocalDateTime lastSuccessAt;
    private LocalDateTime lastFailureAt;
    private LocalDateTime nextRunAt;
    private Long lastDurationMs;
    private Integer consecutiveFailures = 0;
    private Long runCount = 0L;
    private String lastError;
    private LocalDateTime updatedAt;
}
