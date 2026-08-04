package moe.dazecake.inquisition.model.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
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
@TableName("account_scheduled_run")
public class AccountScheduledRunEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long accountId;
    private LocalDateTime scheduledFor;
    private LocalDate gameDay;
    private String status;
    private Integer attemptCount = 0;
    private LocalDateTime nextRetryAt;
    private String lastError;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
    private LocalDateTime finishedAt;
}
