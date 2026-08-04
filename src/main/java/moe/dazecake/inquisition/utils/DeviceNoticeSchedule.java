package moe.dazecake.inquisition.utils;

public final class DeviceNoticeSchedule {
    private DeviceNoticeSchedule() {
    }

    public static int noticeLevel(long offlineMinutes) {
        if (offlineMinutes < 30) {
            return 0;
        }
        if (offlineMinutes < 60) {
            return 30;
        }
        return (int) (offlineMinutes / 60) * 60;
    }

    public static int nextNoticeLevel(long offlineMinutes, int lastNoticeLevel) {
        var currentLevel = noticeLevel(offlineMinutes);
        return currentLevel > lastNoticeLevel ? currentLevel : -1;
    }
}
