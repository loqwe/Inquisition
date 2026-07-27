package moe.dazecake.inquisition.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Accessors(chain = true)
@TableName("urgent_task")
public class UrgentTaskEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long accountId;
    private LocalDate gameDay;
    private String triggerType;
    private String taskMode;
    private Integer priority;
    private String status;
    private Integer attemptCount;
    private LocalDateTime nextRetryAt;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
