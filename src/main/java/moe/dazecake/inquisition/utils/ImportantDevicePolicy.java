package moe.dazecake.inquisition.utils;

import moe.dazecake.inquisition.model.entity.DeviceEntity;

import java.util.Locale;
import java.util.Set;

public final class ImportantDevicePolicy {
    public static final String NOTICE_PREFIX = "[审判庭重点设备]";
    private static final Set<String> IMPORTANT_NAMES = Set.of("A", "1", "2");

    private ImportantDevicePolicy() {
    }

    public static boolean includes(DeviceEntity device) {
        if (device == null || device.getDeviceName() == null) {
            return false;
        }
        var normalizedName = device.getDeviceName().trim().toUpperCase(Locale.ROOT);
        return IMPORTANT_NAMES.contains(normalizedName);
    }
}
