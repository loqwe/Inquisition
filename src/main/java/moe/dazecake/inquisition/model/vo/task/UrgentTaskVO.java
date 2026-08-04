package moe.dazecake.inquisition.model.vo.task;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
public class UrgentTaskVO {
    private Long id;
    private Long accountId;
    private String name;
    private String account;
    private LocalDate gameDay;
    private String triggerType;
    private String taskMode;
    private String status;
    private Integer attemptCount;
    private LocalDateTime nextRetryAt;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String deviceToken;
    private LocalDateTime assignedAt;
    private String lastProgressTitle;
}
