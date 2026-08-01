package moe.dazecake.inquisition.utils;

import moe.dazecake.inquisition.model.entity.DeviceEntity;

import java.util.Locale;
import java.util.Set;

public final class ImportantDevicePolicy {
    public static final String NOTICE_PREFIX = "[审判庭重点设备]";
    public static final String IMPORTANT = "IMPORTANT";
    public static final String BACKUP = "BACKUP";
    private static final Set<String> IMPORTANT_NAMES = Set.of("A", "1", "2");

    private ImportantDevicePolicy() {
    }

    public static boolean includes(DeviceEntity device) {
        if (device == null) {
            return false;
        }
        if (BACKUP.equalsIgnoreCase(device.getDeviceRole())) {
            return false;
        }
        if (IMPORTANT.equalsIgnoreCase(device.getDeviceRole())) {
            return true;
        }
        if (device.getDeviceName() == null) {
            return false;
        }
        var normalizedName = device.getDeviceName().trim().toUpperCase(Locale.ROOT);
        return IMPORTANT_NAMES.contains(normalizedName);
    }

    public static String normalize(String role) {
        return IMPORTANT.equalsIgnoreCase(role) ? IMPORTANT
                : BACKUP.equalsIgnoreCase(role) ? BACKUP : null;
    }

    public static String normalizeNew(String role) {
        var normalized = normalize(role);
        return normalized == null ? BACKUP : normalized;
    }

    public static boolean isBackup(DeviceEntity device) {
        return device != null && BACKUP.equalsIgnoreCase(device.getDeviceRole());
    }
}
