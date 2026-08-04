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
@TableName("account_runtime")
public class AccountRuntimeEntity {
    @TableId(type = IdType.INPUT)
    private Long accountId;
    private LocalDateTime lastValidLogAt;
    private LocalDateTime lastLoginAt;
    private LocalDateTime lastTaskCompletedAt;
    private LocalDateTime lastSklandQueryAt;
    private LocalDateTime lastOnlineAt;
    private Integer sanity;
    private Integer maxSanity;
    private LocalDateTime sanityObservedAt;
    private String sanitySource;
    private LocalDateTime nextEligibleAt;
    private Integer retryCount = 0;
    private LocalDateTime lastFailureAt;
    private String lastFailureDeviceToken;
    private LocalDate gameDayKey;
    private Integer missingLogNotified = 0;
    private Integer abnormal = 0;
    private String lastError;
    private LocalDateTime updatedAt;
}
