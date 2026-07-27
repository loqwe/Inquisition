package moe.dazecake.inquisition.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeviceNoticeScheduleTest {

    @Test
    void usesTheRequestedMilestones() {
        assertEquals(0, DeviceNoticeSchedule.noticeLevel(2));
        assertEquals(0, DeviceNoticeSchedule.noticeLevel(5));
        assertEquals(0, DeviceNoticeSchedule.noticeLevel(15));
        assertEquals(0, DeviceNoticeSchedule.noticeLevel(29));
        assertEquals(30, DeviceNoticeSchedule.noticeLevel(30));
        assertEquals(60, DeviceNoticeSchedule.noticeLevel(60));
    }

    @Test
    void repeatsEveryHourAfterTheFirstHour() {
        assertEquals(120, DeviceNoticeSchedule.noticeLevel(121));
        assertEquals(180, DeviceNoticeSchedule.noticeLevel(180));
        assertEquals(180, DeviceNoticeSchedule.noticeLevel(239));
    }

    @Test
    void doesNotRepeatTheSameNoticeLevel() {
        assertEquals(-1, DeviceNoticeSchedule.nextNoticeLevel(29, 0));
        assertEquals(30, DeviceNoticeSchedule.nextNoticeLevel(30, 0));
        assertEquals(-1, DeviceNoticeSchedule.nextNoticeLevel(45, 30));
        assertEquals(60, DeviceNoticeSchedule.nextNoticeLevel(61, 30));
    }
}
