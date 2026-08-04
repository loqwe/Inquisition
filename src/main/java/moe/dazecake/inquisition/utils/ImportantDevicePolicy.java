package moe.dazecake.inquisition.utils;

import moe.dazecake.inquisition.model.entity.DeviceEntity;

public final class ImportantDevicePolicy {
    public static final String NOTICE_PREFIX = "[审判庭重点设备]";
    public static final String IMPORTANT = DeviceRolePolicy.IMPORTANT;
    public static final String BACKUP = DeviceRolePolicy.BACKUP;

    private ImportantDevicePolicy() {
    }

    public static boolean includes(DeviceEntity device) {
        return DeviceRolePolicy.isImportant(device);
    }

    public static String normalize(String role) {
        return DeviceRolePolicy.normalize(role);
    }

    public static String normalizeNew(String role) {
        return DeviceRolePolicy.normalizeNew(role);
    }

    public static boolean isBackup(DeviceEntity device) {
        return DeviceRolePolicy.isBackup(device);
    }
}
