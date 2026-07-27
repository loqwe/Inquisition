package moe.dazecake.inquisition.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
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
    private LocalTime scheduleTime;
    private LocalDateTime nextScheduledAt;
    private Integer activationPending = 0;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
