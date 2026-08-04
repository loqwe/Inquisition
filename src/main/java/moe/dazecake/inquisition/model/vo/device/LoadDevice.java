package moe.dazecake.inquisition.model.vo.device;

import lombok.Data;
import lombok.NoArgsConstructor;
import moe.dazecake.inquisition.model.entity.DeviceEntity;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class LoadDevice extends DeviceEntity {
    Integer status;
    String runtimeState;
    LocalDateTime lastHeartbeatAt;
    LocalDateTime offlineSince;
    LocalDateTime suspendedUntil;
    Long currentAccountId;
    String currentAccountName;
}
