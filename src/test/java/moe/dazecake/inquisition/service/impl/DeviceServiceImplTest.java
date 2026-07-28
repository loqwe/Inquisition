package moe.dazecake.inquisition.service.impl;

import moe.dazecake.inquisition.model.entity.DeviceEntity;
import moe.dazecake.inquisition.model.vo.device.DeviceRuntimeProjection;
import moe.dazecake.inquisition.utils.DynamicInfo;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeviceServiceImplTest {

    @Test
    void loadedDeviceKeepsLegacyFieldsAndAddsRuntimeProjection() {
        var service = new DeviceServiceImpl();
        service.dynamicInfo = new DynamicInfo();
        service.deviceRuntimeProjectionService = mock(DeviceRuntimeProjectionService.class);
        service.dynamicInfo.getDeviceStatusMap().put("device-token", 1);
        var now = LocalDateTime.of(2026, 7, 29, 10, 0);
        var device = new DeviceEntity().setId(1L).setDeviceName("设备A")
                .setDeviceToken("device-token").setDelete(0).setChinac(0);
        var projection = new DeviceRuntimeProjection().setDevice(device)
                .setRuntimeState(DeviceRuntimeProjectionService.BUSY)
                .setLastHeartbeatAt(now.minusMinutes(1))
                .setCurrentAccountId(7L).setCurrentAccountName("账号7");
        when(service.deviceRuntimeProjectionService.project(any())).thenReturn(List.of(projection));

        var loaded = service.getLoadDevice().getLoadDeviceList().get(0);

        assertEquals("device-token", loaded.getDeviceToken());
        assertEquals(1, loaded.getStatus());
        assertEquals(DeviceRuntimeProjectionService.BUSY, loaded.getRuntimeState());
        assertEquals(7L, loaded.getCurrentAccountId());
        assertEquals("账号7", loaded.getCurrentAccountName());
    }
}
