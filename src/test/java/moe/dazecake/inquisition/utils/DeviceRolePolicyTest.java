package moe.dazecake.inquisition.utils;

import moe.dazecake.inquisition.model.entity.DeviceEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeviceRolePolicyTest {

    @Test
    void legacyImportantNameWithoutRoleRemainsImportant() {
        var device = new DeviceEntity().setDeviceName("A");

        assertTrue(DeviceRolePolicy.isImportant(device));
        assertFalse(DeviceRolePolicy.isBackup(device));
    }

    @Test
    void legacyNonImportantNameWithoutRoleIsBackup() {
        var device = new DeviceEntity().setDeviceName("B");

        assertFalse(DeviceRolePolicy.isImportant(device));
        assertTrue(DeviceRolePolicy.isBackup(device));
    }

    @Test
    void explicitRoleOverridesLegacyName() {
        assertFalse(DeviceRolePolicy.isImportant(new DeviceEntity()
                .setDeviceName("A")
                .setDeviceRole(DeviceRolePolicy.BACKUP)));
        assertTrue(DeviceRolePolicy.isImportant(new DeviceEntity()
                .setDeviceName("B")
                .setDeviceRole(DeviceRolePolicy.IMPORTANT)));
    }

    @Test
    void backupDeviceIsExcludedFromAdminFaultNotifications() {
        var device = new DeviceEntity().setDeviceRole(DeviceRolePolicy.BACKUP);

        assertFalse(DeviceRolePolicy.isImportant(device));
        assertTrue(DeviceRolePolicy.isBackup(device));
    }
}
