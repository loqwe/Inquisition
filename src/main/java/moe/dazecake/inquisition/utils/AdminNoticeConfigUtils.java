package moe.dazecake.inquisition.utils;

import com.google.gson.Gson;
import moe.dazecake.inquisition.model.dto.admin.AdminNoticeConfigDTO;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;

public class AdminNoticeConfigUtils {
    public static final String DEFAULT_SUMMARY_SCHEDULE = "00:00 / 08:00 / 12:00 / 16:00 / 18:00";
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private AdminNoticeConfigUtils() {
    }

    public static AdminNoticeConfigDTO parse(Gson gson, String notice, boolean defaultMailEnable, String defaultAdminMail) {
        try {
            if (notice == null || notice.isBlank()) {
                return normalize(new AdminNoticeConfigDTO(), defaultMailEnable, defaultAdminMail);
            }
            var config = gson.fromJson(notice, AdminNoticeConfigDTO.class);
            return normalize(config, defaultMailEnable, defaultAdminMail);
        } catch (Exception e) {
            return normalize(new AdminNoticeConfigDTO(), defaultMailEnable, defaultAdminMail);
        }
    }

    public static AdminNoticeConfigDTO normalize(AdminNoticeConfigDTO configDTO, boolean defaultMailEnable, String defaultAdminMail) {
        var config = configDTO == null ? new AdminNoticeConfigDTO() : configDTO;
        config.setMailEnable(config.getMailEnable() != null ? config.getMailEnable() : defaultMailEnable);
        config.setAdminMail(config.getAdminMail() == null ? safeTrim(defaultAdminMail) : safeTrim(config.getAdminMail()));
        config.setSummarySchedule(normalizeSummarySchedule(config.getSummarySchedule()));
        config.setWxPusherEnable(Boolean.TRUE.equals(config.getWxPusherEnable()));
        config.setWxPusherUid(safeTrim(config.getWxPusherUid()));
        config.setPushPlusEnable(Boolean.TRUE.equals(config.getPushPlusEnable()));
        config.setPushPlusToken(safeTrim(config.getPushPlusToken()));
        return config;
    }

    public static boolean matchesSchedule(String summarySchedule, LocalTime currentTime) {
        return extractScheduleTimes(summarySchedule).contains(currentTime.format(TIME_FORMATTER));
    }

    public static String normalizeSummarySchedule(String summarySchedule) {
        var times = extractScheduleTimes(summarySchedule);
        return times.isEmpty() ? DEFAULT_SUMMARY_SCHEDULE : String.join(" / ", times);
    }

    private static LinkedHashSet<String> extractScheduleTimes(String summarySchedule) {
        var times = new LinkedHashSet<String>();
        if (summarySchedule == null || summarySchedule.isBlank()) {
            return times;
        }
        for (var token : summarySchedule.split("[^0-9:]+")) {
            if (token == null || token.isBlank()) {
                continue;
            }
            var parts = token.split(":");
            if (parts.length != 2) {
                continue;
            }
            try {
                var hour = Integer.parseInt(parts[0]);
                var minute = Integer.parseInt(parts[1]);
                if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
                    continue;
                }
                times.add(String.format("%02d:%02d", hour, minute));
            } catch (NumberFormatException ignored) {
            }
        }
        return times;
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }
}
