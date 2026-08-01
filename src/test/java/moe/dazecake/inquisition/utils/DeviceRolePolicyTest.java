package moe.dazecake.inquisition.utils;

import moe.dazecake.inquisition.model.entity.DeviceEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeviceRolePolicyTest {

    @Test
    void legacyDeviceWithoutRoleRemainsImportant() {
        var device = new DeviceEntity();

        assertTrue(DeviceRolePolicy.isImportant(device));
        assertFalse(DeviceRolePolicy.isBackup(device));
    }

    @Test
    void backupDeviceIsExcludedFromAdminFaultNotifications() {
        var device = new DeviceEntity().setDeviceRole(DeviceRolePolicy.BACKUP);

        assertFalse(DeviceRolePolicy.isImportant(device));
        assertTrue(DeviceRolePolicy.isBackup(device));
    }
}
