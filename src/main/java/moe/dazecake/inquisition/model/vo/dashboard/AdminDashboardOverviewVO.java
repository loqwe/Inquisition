package moe.dazecake.inquisition.model.vo.dashboard;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
public class AdminDashboardOverviewVO {
    private String generatedAt;
    private String timeZone = "Asia/Shanghai";
    private String gameDay;
    private String gameDayStartedAt;
    private String overallStatus = "HEALTHY";
    private int alertCount;
    private Accounts accounts = new Accounts();
    private Tasks tasks = new Tasks();
    private Devices devices = new Devices();
    private ScheduledTasks scheduledTasks = new ScheduledTasks();
    private Business business = new Business();
    private List<AlertItem> alerts = new ArrayList<>();

    @Data
    @Accessors(chain = true)
    public static class Accounts {
        private long eligibleDaily;
        private long loggedToday;
        private long missingLogin;
        private double loginRate;
        private long frozen;
        private long coolingDown;
        private long expiringWithinSevenDays;
        private List<MissingAccountItem> missingItems = new ArrayList<>();
    }

    @Data
    @Accessors(chain = true)
    public static class MissingAccountItem {
        private Long accountId;
        private String name;
        private String dispatchMode;
        private String nextScheduledAt;
        private String currentTaskState;
    }

    @Data
    @Accessors(chain = true)
    public static class Tasks {
        private int urgent;
        private int pending;
        private int inProgress;
        private int scheduledWaiting;
        private int scheduledRunning;
        private int longRunning;
        private List<TaskItem> runningItems = new ArrayList<>();
        private List<TaskItem> priorityWaitingItems = new ArrayList<>();
    }

    @Data
    @Accessors(chain = true)
    public static class TaskItem {
        private String assignmentId;
        private Long accountId;
        private String name;
        private String taskMode;
        private String dispatchSource;
        private String deviceName;
        private String assignedAt;
        private long runningMinutes;
        private String lastProgressTitle;
        private String leaseExpiresAt;
        private boolean urgent;
    }

    @Data
    @Accessors(chain = true)
    public static class Devices {
        private int total;
        private int online;
        private int idle;
        private int busy;
        private int offline;
        private int suspended;
        private List<DeviceItem> items = new ArrayList<>();
    }

    @Data
    @Accessors(chain = true)
    public static class DeviceItem {
        private Long deviceId;
        private String name;
        private String tokenSuffix;
        private String runtimeState;
        private String lastHeartbeatAt;
        private String offlineSince;
        private String suspendedUntil;
        private Long currentAccountId;
        private String currentAccountName;
    }

    @Data
    @Accessors(chain = true)
    public static class ScheduledTasks {
        private int total;
        private int healthy;
        private int running;
        private int abnormal;
        private int waiting;
        private int disabled;
        private List<ScheduledTaskItem> abnormalItems = new ArrayList<>();
    }

    @Data
    @Accessors(chain = true)
    public static class ScheduledTaskItem {
        private String key;
        private String name;
        private String status;
        private String lastSuccessAt;
        private String lastFailureAt;
        private String nextRunAt;
        private int consecutiveFailures;
        private String lastError;
    }

    @Data
    @Accessors(chain = true)
    public static class Business {
        private long newAccountsToday;
        private long validAccounts;
        private double dayIncome;
        private double monthIncome;
    }

    @Data
    @Accessors(chain = true)
    public static class AlertItem {
        private String type;
        private String severity;
        private String title;
        private String detail;
        private String since;
        private String href;
    }
}
