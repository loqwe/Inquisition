package moe.dazecake.inquisition.model.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.time.LocalTime;

@Data
@NoArgsConstructor
@Accessors(chain = true)
@TableName("account_dispatch_config")
public class AccountDispatchConfigEntity {
    @TableId(type = IdType.INPUT)
    private Long accountId;
    private String dispatchMode = "AUTO";
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private LocalTime scheduleTime;
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private LocalDateTime nextScheduledAt;
    private Integer activationPending = 0;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}
