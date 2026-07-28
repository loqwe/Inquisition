package moe.dazecake.inquisition.model.vo.device;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import moe.dazecake.inquisition.model.entity.DeviceEntity;
import moe.dazecake.inquisition.service.impl.DeviceRuntimeProjectionService;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Accessors(chain = true)
public class DeviceRuntimeProjection {
    private DeviceEntity device;
    private String runtimeState;
    private LocalDateTime lastHeartbeatAt;
    private LocalDateTime offlineSince;
    private LocalDateTime suspendedUntil;
    private Long currentAccountId;
    private String currentAccountName;

    public boolean isOnline() {
        return !DeviceRuntimeProjectionService.OFFLINE.equals(runtimeState);
    }
}
