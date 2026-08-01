package moe.dazecake.inquisition.utils;

import moe.dazecake.inquisition.model.entity.DeviceEntity;

public final class DeviceRolePolicy {
    public static final String IMPORTANT = "IMPORTANT";
    public static final String BACKUP = "BACKUP";

    private DeviceRolePolicy() {
    }

    public static String normalize(String role) {
        return IMPORTANT.equalsIgnoreCase(role) ? IMPORTANT
                : BACKUP.equalsIgnoreCase(role) ? BACKUP : IMPORTANT;
    }

    public static String normalizeNew(String role) {
        return role == null || role.isBlank() ? BACKUP : normalize(role);
    }

    public static boolean isBackup(DeviceEntity device) {
        return device != null && BACKUP.equalsIgnoreCase(device.getDeviceRole());
    }

    public static boolean isImportant(DeviceEntity device) {
        return !isBackup(device);
    }
}
