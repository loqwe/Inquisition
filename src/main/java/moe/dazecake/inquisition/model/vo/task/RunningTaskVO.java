package moe.dazecake.inquisition.model.vo.task;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
public class RunningTaskVO {
    private String assignmentId;
    private Long accountId;
    private String name;
    private String account;
    private String taskType;
    private String taskMode;
    private Boolean urgent;
    private String deviceToken;
    private LocalDateTime assignedAt;
    private Long runningMinutes;
    private LocalDateTime lastProgressAt;
    private String lastProgressTitle;
    private String lastProgressDetail;
    private LocalDateTime leaseExpiresAt;
}
