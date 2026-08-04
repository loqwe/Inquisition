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
@TableName("task_assignment_history")
public class TaskAssignmentHistoryEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String assignmentId;
    private Long accountId;
    private String deviceToken;
    private String taskType;
    private String taskMode;
    private Long urgentTaskId;
    private String dispatchSource = "AUTO";
    private Long scheduledRunId;
    private String status;
    private LocalDateTime assignedAt;
    private LocalDateTime leaseExpiresAt;
    private LocalDateTime lastProgressAt;
    private Integer gameStarted;
    private String lastProgressTitle;
    private String lastProgressDetail;
    private Integer retryCount;
    private Integer longTaskNotified;
    private String reason;
    private LocalDateTime finishedAt;
}
