package moe.dazecake.inquisition.utils;

import moe.dazecake.inquisition.model.entity.DeviceEntity;

import java.util.List;

public final class DeviceScopeUtil {

    private static final List<String> LEGACY_ALL_SCOPE = List.of("daily", "b_daily", "rogue", "rogue2", "sand_fire");

    private DeviceScopeUtil() {
    }

    public static boolean supports(DeviceEntity device, String scope) {
        var workScope = device == null ? null : device.getWorkScope();
        if (workScope == null || workScope.isEmpty()) {
            return LEGACY_ALL_SCOPE.contains(scope);
        }
        return workScope.contains(scope);
    }
}