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
@TableName("task_assignment")
public class TaskAssignmentEntity {
    @TableId(type = IdType.INPUT)
    private String assignmentId;
    private Long accountId;
    private String deviceToken;
    private String taskType;
    private String taskMode = "NORMAL";
    private Long urgentTaskId;
    private String dispatchSource = "AUTO";
    private Long scheduledRunId;
    private LocalDateTime assignedAt;
    private LocalDateTime leaseExpiresAt;
    private LocalDateTime lastProgressAt;
    private Integer gameStarted = 0;
    private String lastProgressTitle;
    private String lastProgressDetail;
    private Integer retryCount = 0;
    private Integer longTaskNotified = 0;
}
