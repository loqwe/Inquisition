package moe.dazecake.inquisition.utils;

import moe.dazecake.inquisition.model.entity.DeviceEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportantDevicePolicyTest {

    @Test
    void onlyAOneAndTwoAreImportantDevices() {
        assertTrue(ImportantDevicePolicy.includes(device("A")));
        assertTrue(ImportantDevicePolicy.includes(device("1")));
        assertTrue(ImportantDevicePolicy.includes(device("2")));
        assertTrue(ImportantDevicePolicy.includes(device(" a ")));

        assertFalse(ImportantDevicePolicy.includes(device("B")));
        assertFalse(ImportantDevicePolicy.includes(device("3")));
        assertFalse(ImportantDevicePolicy.includes(device("设备A")));
        assertFalse(ImportantDevicePolicy.includes(null));
    }

    @Test
    void explicitRoleOverridesLegacyNameForNotifications() {
        assertFalse(ImportantDevicePolicy.includes(device("A")
                .setDeviceRole(DeviceRolePolicy.BACKUP)));
        assertTrue(ImportantDevicePolicy.includes(device("B")
                .setDeviceRole(DeviceRolePolicy.IMPORTANT)));
    }

    private DeviceEntity device(String name) {
        return new DeviceEntity().setDeviceName(name);
    }
}
