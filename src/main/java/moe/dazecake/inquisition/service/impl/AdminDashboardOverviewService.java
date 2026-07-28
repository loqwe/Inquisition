package moe.dazecake.inquisition.service.impl;

import moe.dazecake.inquisition.mapper.DashboardMetricsMapper;
import moe.dazecake.inquisition.model.local.DispatchIntent;
import moe.dazecake.inquisition.model.vo.account.AccountWithSanVO;
import moe.dazecake.inquisition.model.vo.dashboard.AdminDashboardOverviewVO;
import moe.dazecake.inquisition.model.vo.dashboard.DashboardAccountMetrics;
import moe.dazecake.inquisition.model.vo.dashboard.DashboardBusinessMetrics;
import moe.dazecake.inquisition.model.vo.device.DeviceRuntimeProjection;
import moe.dazecake.inquisition.model.vo.query.PageQueryVO;
import moe.dazecake.inquisition.model.vo.task.RunningTaskVO;
import moe.dazecake.inquisition.model.vo.task.ScheduledTaskOverviewVO;
import moe.dazecake.inquisition.model.vo.task.ScheduledTaskStatusVO;
import moe.dazecake.inquisition.model.vo.task.TaskBoardVO;
import moe.dazecake.inquisition.utils.GameDayClock;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AdminDashboardOverviewService {
    private static final DateTimeFormatter OFFSET_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    private static final Set<String> ABNORMAL_SCHEDULED_STATUSES =
            Set.of("FAILED", "MISSED", "STALLED");

    @Resource
    DashboardMetricsMapper dashboardMetricsMapper;

    @Resource
    AccountServiceImpl accountService;

    @Resource
    TaskBoardService taskBoardService;

    @Resource
    DeviceRuntimeProjectionService deviceRuntimeProjectionService;

    @Resource
    ScheduledTaskMonitorService scheduledTaskMonitorService;

    public AdminDashboardOverviewVO getOverview(LocalDateTime requestedNow) {
        var now = requestedNow == null ? GameDayClock.now() : requestedNow;
        var gameDayStart = GameDayClock.startOfGameDay(now);
        var gameDayEnd = gameDayStart.plusDays(1);
        var monthStart = now.toLocalDate().withDayOfMonth(1).atStartOfDay();
        var monthEnd = monthStart.plusMonths(1);

        var accountMetrics = dashboardMetricsMapper.selectAccountMetrics(
                now, gameDayStart, gameDayEnd, now.plusDays(7));
        if (accountMetrics == null) {
            accountMetrics = new DashboardAccountMetrics();
        }
        var businessMetrics = dashboardMetricsMapper.selectBusinessMetrics(
                gameDayStart, gameDayEnd, monthStart, monthEnd);
        if (businessMetrics == null) {
            businessMetrics = new DashboardBusinessMetrics();
        }
        var board = taskBoardService.getReadOnlySnapshot(now);
        if (board == null) {
            board = new TaskBoardVO();
        }
        var missingPage = accountService.queryAllAccount(1L, 5L, null, null,
                null, null, "missing", now);
        if (missingPage == null) {
            missingPage = new PageQueryVO<>();
        }
        var deviceProjections = deviceRuntimeProjectionService.project(now, board.getRunningTasks());
        if (deviceProjections == null) {
            deviceProjections = List.of();
        }
        var scheduledOverview = scheduledTaskMonitorService.getOverview(now);

        var accounts = accounts(accountMetrics, missingPage, board);
        var tasks = tasks(board);
        var devices = devices(deviceProjections);
        var scheduledTasks = scheduledTasks(scheduledOverview);
        var business = new AdminDashboardOverviewVO.Business()
                .setNewAccountsToday(value(accountMetrics.getNewAccountsToday()))
                .setValidAccounts(value(accountMetrics.getValidAccounts()))
                .setDayIncome(value(businessMetrics.getDayIncome()))
                .setMonthIncome(value(businessMetrics.getMonthIncome()));
        var alerts = alerts(now, gameDayStart, accounts, tasks, devices,
                deviceProjections, scheduledOverview, board);
        var alertCount = alertCount(now, gameDayStart, accounts, tasks, devices, scheduledTasks);
        var overallStatus = alerts.stream().anyMatch(alert -> "CRITICAL".equals(alert.getSeverity()))
                ? "CRITICAL" : alertCount > 0 ? "WARNING" : "HEALTHY";

        return new AdminDashboardOverviewVO()
                .setGeneratedAt(offset(now))
                .setTimeZone(GameDayClock.ZONE_ID.getId())
                .setGameDay(GameDayClock.gameDay(now).toString())
                .setGameDayStartedAt(offset(gameDayStart))
                .setOverallStatus(overallStatus)
                .setAlertCount(alertCount)
                .setAccounts(accounts)
                .setTasks(tasks)
                .setDevices(devices)
                .setScheduledTasks(scheduledTasks)
                .setBusiness(business)
                .setAlerts(alerts.stream().limit(20).collect(Collectors.toList()));
    }

    private AdminDashboardOverviewVO.Accounts accounts(DashboardAccountMetrics metrics,
                                                        PageQueryVO<AccountWithSanVO> missingPage,
                                                        TaskBoardVO board) {
        var eligible = value(metrics.getEligibleDaily());
        var missing = Math.min(eligible, value(missingPage.getTotal()));
        var logged = Math.max(0L, eligible - missing);
        var result = new AdminDashboardOverviewVO.Accounts()
                .setEligibleDaily(eligible)
                .setLoggedToday(logged)
                .setMissingLogin(missing)
                .setLoginRate(eligible == 0 ? 100.0 : roundOneDecimal(logged * 100.0 / eligible))
                .setFrozen(value(metrics.getFrozen()))
                .setCoolingDown(board.getCooldownTasks() == null ? 0 : board.getCooldownTasks().size())
                .setExpiringWithinSevenDays(value(metrics.getExpiringWithinSevenDays()));
        var records = missingPage.getRecords() == null ? List.<AccountWithSanVO>of() : missingPage.getRecords();
        records.stream().filter(Objects::nonNull).limit(5).forEach(account ->
                result.getMissingItems().add(new AdminDashboardOverviewVO.MissingAccountItem()
                        .setAccountId(account.getId())
                        .setName(account.getName())
                        .setDispatchMode(account.getDispatchMode() == null ? "AUTO" : account.getDispatchMode())
                        .setNextScheduledAt(offset(account.getNextScheduledAt()))
                        .setCurrentTaskState(taskState(board, account.getId()))));
        return result;
    }

    private AdminDashboardOverviewVO.Tasks tasks(TaskBoardVO board) {
        var urgentTasks = board.getUrgentTasks() == null ? List.<moe.dazecake.inquisition.model.vo.task.UrgentTaskVO>of()
                : board.getUrgentTasks();
        var pendingTasks = board.getPendingTasks() == null ? List.<moe.dazecake.inquisition.model.vo.task.TaskBoardAccountVO>of()
                : board.getPendingTasks();
        var runningTasks = board.getRunningTasks() == null ? List.<RunningTaskVO>of() : board.getRunningTasks();
        var result = new AdminDashboardOverviewVO.Tasks()
                .setUrgent(urgentTasks.size())
                .setPending(pendingTasks.size())
                .setInProgress(runningTasks.size())
                .setScheduledWaiting((int) pendingTasks.stream()
                        .filter(task -> DispatchIntent.SOURCE_SCHEDULED.equals(task.getDispatchSource())).count())
                .setScheduledRunning((int) runningTasks.stream()
                        .filter(task -> DispatchIntent.SOURCE_SCHEDULED.equals(task.getDispatchSource())).count())
                .setLongRunning((int) runningTasks.stream().filter(this::isLongRunning).count());
        runningTasks.stream().sorted(Comparator
                        .comparing((RunningTaskVO task) -> Boolean.TRUE.equals(task.getUrgent())).reversed()
                        .thenComparing(RunningTaskVO::getAssignedAt,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(5).map(this::taskItem).forEach(result.getRunningItems()::add);
        urgentTasks.stream().filter(task -> task.getAssignedAt() == null).limit(5).forEach(task ->
                result.getPriorityWaitingItems().add(new AdminDashboardOverviewVO.TaskItem()
                        .setAccountId(task.getAccountId()).setName(task.getName())
                        .setTaskMode(task.getTaskMode()).setDispatchSource(DispatchIntent.SOURCE_URGENT_26)
                        .setAssignedAt(offset(task.getCreatedAt())).setUrgent(true)
                        .setLastProgressTitle(task.getLastProgressTitle())));
        if (result.getPriorityWaitingItems().size() < 5) {
            pendingTasks.stream().filter(task -> DispatchIntent.SOURCE_SCHEDULED.equals(task.getDispatchSource())
                            || DispatchIntent.SOURCE_MANUAL.equals(task.getDispatchSource()))
                    .limit(5 - result.getPriorityWaitingItems().size()).forEach(task ->
                            result.getPriorityWaitingItems().add(new AdminDashboardOverviewVO.TaskItem()
                                    .setAccountId(task.getId()).setName(task.getName())
                                    .setTaskMode("NORMAL").setDispatchSource(task.getDispatchSource())));
        }
        return result;
    }

    private AdminDashboardOverviewVO.TaskItem taskItem(RunningTaskVO task) {
        return new AdminDashboardOverviewVO.TaskItem()
                .setAssignmentId(task.getAssignmentId())
                .setAccountId(task.getAccountId())
                .setName(task.getName())
                .setTaskMode(task.getTaskMode())
                .setDispatchSource(task.getDispatchSource())
                .setDeviceName(task.getDeviceName())
                .setAssignedAt(offset(task.getAssignedAt()))
                .setRunningMinutes(task.getRunningMinutes() == null ? 0 : task.getRunningMinutes())
                .setLastProgressTitle(task.getLastProgressTitle())
                .setLeaseExpiresAt(offset(task.getLeaseExpiresAt()))
                .setUrgent(Boolean.TRUE.equals(task.getUrgent()));
    }

    private AdminDashboardOverviewVO.Devices devices(List<DeviceRuntimeProjection> projections) {
        var result = new AdminDashboardOverviewVO.Devices().setTotal(projections.size());
        projections.forEach(projection -> {
            switch (projection.getRuntimeState()) {
                case DeviceRuntimeProjectionService.IDLE:
                    result.setIdle(result.getIdle() + 1);
                    result.setOnline(result.getOnline() + 1);
                    break;
                case DeviceRuntimeProjectionService.BUSY:
                    result.setBusy(result.getBusy() + 1);
                    result.setOnline(result.getOnline() + 1);
                    break;
                case DeviceRuntimeProjectionService.SUSPENDED:
                    result.setSuspended(result.getSuspended() + 1);
                    result.setOnline(result.getOnline() + 1);
                    break;
                default:
                    result.setOffline(result.getOffline() + 1);
                    break;
            }
        });
        projections.stream().sorted(Comparator
                        .comparingInt((DeviceRuntimeProjection projection) -> deviceRank(projection.getRuntimeState()))
                        .thenComparing(projection -> projection.getDevice() == null ? null
                                        : projection.getDevice().getDeviceName(),
                                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .limit(5).map(this::deviceItem).forEach(result.getItems()::add);
        return result;
    }

    private AdminDashboardOverviewVO.DeviceItem deviceItem(DeviceRuntimeProjection projection) {
        var device = projection.getDevice();
        var token = device == null ? null : device.getDeviceToken();
        return new AdminDashboardOverviewVO.DeviceItem()
                .setDeviceId(device == null ? null : device.getId())
                .setName(device == null ? null : device.getDeviceName())
                .setTokenSuffix(tokenSuffix(token))
                .setRuntimeState(projection.getRuntimeState())
                .setLastHeartbeatAt(offset(projection.getLastHeartbeatAt()))
                .setOfflineSince(offset(projection.getOfflineSince()))
                .setSuspendedUntil(offset(projection.getSuspendedUntil()))
                .setCurrentAccountId(projection.getCurrentAccountId())
                .setCurrentAccountName(projection.getCurrentAccountName());
    }

    private AdminDashboardOverviewVO.ScheduledTasks scheduledTasks(ScheduledTaskOverviewVO overview) {
        var result = new AdminDashboardOverviewVO.ScheduledTasks();
        if (overview == null) {
            return result;
        }
        result.setTotal(overview.getTotalCount()).setHealthy(overview.getHealthyCount())
                .setRunning(overview.getRunningCount()).setAbnormal(overview.getAbnormalCount())
                .setWaiting(overview.getWaitingCount()).setDisabled(overview.getDisabledCount());
        var statuses = overview.getTasks() == null ? List.<ScheduledTaskStatusVO>of() : overview.getTasks();
        statuses.stream().filter(task -> ABNORMAL_SCHEDULED_STATUSES.contains(task.getStatus()))
                .sorted(Comparator.comparingInt((ScheduledTaskStatusVO task) -> scheduledRank(task.getStatus()))
                        .thenComparing(ScheduledTaskStatusVO::getName,
                                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .limit(5).forEach(task -> result.getAbnormalItems().add(
                        new AdminDashboardOverviewVO.ScheduledTaskItem()
                                .setKey(task.getKey()).setName(task.getName()).setStatus(task.getStatus())
                                .setLastSuccessAt(offset(task.getLastSuccessAt()))
                                .setLastFailureAt(offset(task.getLastFailureAt()))
                                .setNextRunAt(offset(task.getNextRunAt()))
                                .setConsecutiveFailures(task.getConsecutiveFailures())
                                .setLastError(task.getLastError())));
        return result;
    }

    private List<AdminDashboardOverviewVO.AlertItem> alerts(LocalDateTime now, LocalDateTime gameDayStart,
                                                             AdminDashboardOverviewVO.Accounts accounts,
                                                             AdminDashboardOverviewVO.Tasks tasks,
                                                             AdminDashboardOverviewVO.Devices devices,
                                                             List<DeviceRuntimeProjection> projections,
                                                             ScheduledTaskOverviewVO scheduledOverview,
                                                             TaskBoardVO board) {
        var alerts = new ArrayList<AdminDashboardOverviewVO.AlertItem>();
        projections.forEach(projection -> {
            if (DeviceRuntimeProjectionService.OFFLINE.equals(projection.getRuntimeState())) {
                alerts.add(alert("DEVICE", "WARNING", deviceName(projection) + " 离线",
                        "设备心跳已超过30分钟", offset(projection.getOfflineSince()),
                        "/admin/devices?state=offline"));
            } else if (DeviceRuntimeProjectionService.SUSPENDED.equals(projection.getRuntimeState())) {
                alerts.add(alert("DEVICE", "WARNING", deviceName(projection) + " 已暂停",
                        "设备暂不参与任务分配", offset(projection.getSuspendedUntil()),
                        "/admin/devices?state=suspended"));
            }
        });
        if (devices.getTotal() > 0 && devices.getOffline() == devices.getTotal()) {
            alerts.add(alert("DEVICE", "CRITICAL", "全部设备离线",
                    "当前没有设备可接收任务", offset(now), "/admin/devices?state=offline"));
        }
        var runningTasks = board.getRunningTasks() == null ? List.<RunningTaskVO>of() : board.getRunningTasks();
        runningTasks.stream().filter(this::isLongRunning).forEach(task -> alerts.add(alert(
                "TASK", Boolean.TRUE.equals(task.getUrgent()) ? "CRITICAL" : "WARNING",
                safeName(task.getName(), "账号" + task.getAccountId()) + " 已运行超过2小时",
                task.getLastProgressTitle(), offset(task.getAssignedAt()), "/admin/tasks?tab=inProgress")));
        var scheduledStatuses = scheduledOverview == null || scheduledOverview.getTasks() == null
                ? List.<ScheduledTaskStatusVO>of() : scheduledOverview.getTasks();
        scheduledStatuses.stream().filter(task -> ABNORMAL_SCHEDULED_STATUSES.contains(task.getStatus()))
                .forEach(task -> alerts.add(alert("SCHEDULED_TASK", "WARNING",
                        safeName(task.getName(), task.getKey()) + " 异常",
                        task.getLastError(), offset(task.getLastFailureAt()),
                        "/admin/scheduled-tasks?filter=ABNORMAL")));
        if (scheduledStatuses.stream().filter(task -> "STALLED".equals(task.getStatus())).count() > 1) {
            alerts.add(alert("SCHEDULED_TASK", "CRITICAL", "多个脚本任务卡住",
                    "请检查后端调度器和最近错误", offset(now),
                    "/admin/scheduled-tasks?filter=ABNORMAL"));
        }
        var loginSeverity = loginSeverity(now, gameDayStart);
        if (loginSeverity != null && accounts.getMissingLogin() > 0) {
            alerts.add(alert("ACCOUNT", loginSeverity,
                    accounts.getMissingLogin() + " 个账号今日未登录",
                    "游戏日从04:00开始统计", offset(gameDayStart),
                    "/admin/users?login=missing"));
        }
        alerts.sort(Comparator.comparingInt((AdminDashboardOverviewVO.AlertItem item) ->
                        "CRITICAL".equals(item.getSeverity()) ? 0 : 1)
                .thenComparing(AdminDashboardOverviewVO.AlertItem::getTitle,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        return alerts;
    }

    private int alertCount(LocalDateTime now, LocalDateTime gameDayStart,
                           AdminDashboardOverviewVO.Accounts accounts,
                           AdminDashboardOverviewVO.Tasks tasks,
                           AdminDashboardOverviewVO.Devices devices,
                           AdminDashboardOverviewVO.ScheduledTasks scheduledTasks) {
        long count = (long) devices.getOffline() + devices.getSuspended()
                + tasks.getLongRunning() + scheduledTasks.getAbnormal();
        if (loginSeverity(now, gameDayStart) != null) {
            count += accounts.getMissingLogin();
        }
        return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
    }

    private String taskState(TaskBoardVO board, Long accountId) {
        if (accountId == null) {
            return "IDLE";
        }
        if (board.getRunningTasks() != null && board.getRunningTasks().stream()
                .anyMatch(task -> accountId.equals(task.getAccountId()))) {
            return "RUNNING";
        }
        if ((board.getUrgentTasks() != null && board.getUrgentTasks().stream()
                .anyMatch(task -> accountId.equals(task.getAccountId())))
                || (board.getPendingTasks() != null && board.getPendingTasks().stream()
                .anyMatch(task -> accountId.equals(task.getId())))) {
            return "PENDING";
        }
        if (board.getCooldownTasks() != null && board.getCooldownTasks().stream()
                .anyMatch(task -> accountId.equals(task.getId()))) {
            return "COOLDOWN";
        }
        return "IDLE";
    }

    private boolean isLongRunning(RunningTaskVO task) {
        return task != null && task.getRunningMinutes() != null && task.getRunningMinutes() >= 120;
    }

    private String loginSeverity(LocalDateTime now, LocalDateTime gameDayStart) {
        if (!now.isBefore(gameDayStart.plusHours(22))) {
            return "CRITICAL";
        }
        if (!now.isBefore(gameDayStart.plusHours(10))) {
            return "WARNING";
        }
        return null;
    }

    private AdminDashboardOverviewVO.AlertItem alert(String type, String severity, String title,
                                                      String detail, String since, String href) {
        return new AdminDashboardOverviewVO.AlertItem().setType(type).setSeverity(severity)
                .setTitle(title).setDetail(detail).setSince(since).setHref(href);
    }

    private int deviceRank(String state) {
        if (DeviceRuntimeProjectionService.OFFLINE.equals(state)) {
            return 0;
        }
        if (DeviceRuntimeProjectionService.SUSPENDED.equals(state)) {
            return 1;
        }
        if (DeviceRuntimeProjectionService.BUSY.equals(state)) {
            return 2;
        }
        return 3;
    }

    private int scheduledRank(String status) {
        if ("STALLED".equals(status)) {
            return 0;
        }
        if ("FAILED".equals(status)) {
            return 1;
        }
        return 2;
    }

    private String tokenSuffix(String token) {
        if (token == null || token.isBlank()) {
            return "";
        }
        return token.length() <= 4 ? token : token.substring(token.length() - 4);
    }

    private String deviceName(DeviceRuntimeProjection projection) {
        return projection.getDevice() == null
                ? "未知设备" : safeName(projection.getDevice().getDeviceName(), "未知设备");
    }

    private String safeName(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String offset(LocalDateTime value) {
        return value == null ? null : value.atZone(GameDayClock.ZONE_ID)
                .toOffsetDateTime().format(OFFSET_FORMATTER);
    }

    private long value(Long value) {
        return value == null ? 0L : value;
    }

    private double value(Double value) {
        return value == null ? 0.0 : value;
    }

    private double roundOneDecimal(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
