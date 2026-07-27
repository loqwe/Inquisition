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
@TableName("device_runtime")
public class DeviceRuntimeEntity {
    @TableId(type = IdType.INPUT)
    private String deviceToken;
    private String state;
    private LocalDateTime lastHeartbeatAt;
    private LocalDateTime offlineSince;
    private Integer lastNoticeLevel = 0;
    private LocalDateTime lastNoticeAt;
    private Integer recoveryPending = 0;
    private Integer consecutiveFailures = 0;
    private Integer lastFailureNoticeCount = 0;
    private LocalDateTime lastFailureNoticeAt;
    private LocalDateTime suspendedUntil;
    private String clientVersion;
    private LocalDateTime updatedAt;
}
