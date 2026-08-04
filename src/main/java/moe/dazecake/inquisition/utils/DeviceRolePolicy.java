package moe.dazecake.inquisition.utils;

import moe.dazecake.inquisition.model.entity.DeviceEntity;

import java.util.Locale;
import java.util.Set;

public final class DeviceRolePolicy {
    public static final String IMPORTANT = "IMPORTANT";
    public static final String BACKUP = "BACKUP";
    private static final Set<String> LEGACY_IMPORTANT_NAMES = Set.of("A", "1", "2");

    private DeviceRolePolicy() {
    }

    public static String normalize(String role) {
        if (role == null) {
            return null;
        }
        var normalized = role.trim();
        return IMPORTANT.equalsIgnoreCase(normalized) ? IMPORTANT
                : BACKUP.equalsIgnoreCase(normalized) ? BACKUP : null;
    }

    public static String normalizeNew(String role) {
        var normalized = normalize(role);
        return normalized == null ? BACKUP : normalized;
    }

    public static String effectiveRole(DeviceEntity device) {
        if (device == null) {
            return BACKUP;
        }
        var explicitRole = normalize(device.getDeviceRole());
        if (explicitRole != null) {
            return explicitRole;
        }
        return isLegacyImportantName(device.getDeviceName()) ? IMPORTANT : BACKUP;
    }

    public static boolean isBackup(DeviceEntity device) {
        return BACKUP.equals(effectiveRole(device));
    }

    public static boolean isImportant(DeviceEntity device) {
        return IMPORTANT.equals(effectiveRole(device));
    }

    private static boolean isLegacyImportantName(String name) {
        return name != null && LEGACY_IMPORTANT_NAMES.contains(name.trim().toUpperCase(Locale.ROOT));
    }
}
