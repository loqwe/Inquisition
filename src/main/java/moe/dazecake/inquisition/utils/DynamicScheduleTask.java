package moe.dazecake.inquisition.utils;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import moe.dazecake.inquisition.mapper.AccountMapper;
import moe.dazecake.inquisition.mapper.AdminMapper;
import moe.dazecake.inquisition.mapper.DeviceMapper;
import moe.dazecake.inquisition.mapper.LogMapper;
import moe.dazecake.inquisition.model.dto.admin.AdminNoticeConfigDTO;
import moe.dazecake.inquisition.model.entity.AccountEntity;
import moe.dazecake.inquisition.model.entity.AdminEntity;
import moe.dazecake.inquisition.model.entity.DeviceEntity;
import moe.dazecake.inquisition.model.entity.LogEntity;
import moe.dazecake.inquisition.service.impl.ChinacServiceImpl;
import moe.dazecake.inquisition.service.impl.DailyLoginSweepService;
import moe.dazecake.inquisition.service.impl.AccountRuntimeService;
import moe.dazecake.inquisition.service.impl.LogServiceImpl;
import moe.dazecake.inquisition.service.impl.MessageServiceImpl;
import moe.dazecake.inquisition.service.impl.DeviceRuntimeService;
import moe.dazecake.inquisition.service.impl.TaskAssignmentService;
import moe.dazecake.inquisition.service.impl.TaskServiceImpl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;

import javax.annotation.Resource;
import javax.annotation.PreDestroy;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.TimeZone;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Configuration
@EnableScheduling
public class DynamicScheduleTask implements SchedulingConfigurer {

    private final Gson gson = new Gson();

    @Resource
    DynamicInfo dynamicInfo;

    @Resource
    AccountMapper accountMapper;

    @Resource
    DeviceMapper deviceMapper;

    @Resource
    AdminMapper adminMapper;

    @Resource
    LogMapper logMapper;

    @Resource
    LogServiceImpl logService;

    @Resource
    MessageServiceImpl messageService;

    @Resource
    TaskServiceImpl taskService;

    @Resource
    DeviceRuntimeService deviceRuntimeService;

    @Resource
    TaskAssignmentService taskAssignmentService;

    @Resource
    AccountRuntimeService accountRuntimeService;

    @Resource
    ChinacServiceImpl chinacService;

    @Resource
    DailyLoginSweepService dailyLoginSweepService;

    @Value("${spring.mail.to:}")
    String to;

    @Value("${spring.mail.enable:false}")
    boolean enableMail;

    @Value("${wx-pusher.enable:false}")
    boolean enableWxPusher;

    @Value("${inquisition.chinac.enableAutoDeviceManage:false}")
    boolean enableAutoDeviceManage;

    @Value("${inquisition.chinac.maxPlayerInDevice:25}")
    Integer maxPlayerInDevice;

    private static final long RECENT_OFFLINE_WINDOW_HOURS = 24;
    private static final long RECENT_TOP_OPERATOR_WINDOW_HOURS = 24;
    Executor missingLogExecutor = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "missing-log-audit");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean missingLogScanInFlight = new AtomicBoolean();

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        //队列巡检
        taskRegistrar.addTriggerTask(
                () -> {
                    //log.info("正在巡检队列: " + LocalDateTime.now().toLocalTime());
                    //检查等待队列中是否存在重复项，若存在删除多余的重复项
                    var restored = taskService.restoreExpiredCooldownTasks();
                    LinkedHashSet<Long> set = new LinkedHashSet<>(dynamicInfo.getWaitUserList());
                    dynamicInfo.setWaitUserList(new ArrayList<>(set));
                    if (restored > 0) {
                        log.info("【审判庭】已恢复冷却到期账号数: " + restored);
                    }
                },
                triggerContext -> new CronTrigger("0 */1 * * * *").nextExecutionTime(triggerContext)
        );
        //理智刷新
        taskRegistrar.addTriggerTask(
                () -> {
                    //log.info("正在刷新用户理智: " + LocalDateTime.now().toLocalTime());
                    taskService.calculatingSan();
                },
                triggerContext -> new CronTrigger("0 */6 * * * *").nextExecutionTime(triggerContext)
        );
        //设备离线监控
        taskRegistrar.addTriggerTask(
                () -> deviceRuntimeService.scan(GameDayClock.now()),
                triggerContext -> new CronTrigger("0 0/5 * * * ?").nextExecutionTime(triggerContext)
        );
        //任务超时检测
        taskRegistrar.addTriggerTask(
                () -> {
                    if (dynamicInfo.getActive()) {
                        var now = GameDayClock.now();
                        notifyLongRunningTasks(now);
                        var expiredAssignments = taskAssignmentService.closeExpiredAssignments(now);
                        if (expiredAssignments > 0) {
                            log.info("【审判庭】 已处理超时任务数: " + expiredAssignments);
                        }
                    }
                },
                triggerContext -> new CronTrigger("0 0/5 * * * ?").nextExecutionTime(triggerContext)
        );
        //账号过期检测
        taskRegistrar.addTriggerTask(
                () -> {
                    log.info("【审判庭】 账号过期检测");
                    var finalTime = GameDayClock.now().plusDays(7);
                    var accountList = accountMapper.selectList(Wrappers.<AccountEntity>lambdaQuery()
                            .lt(AccountEntity::getExpireTime, finalTime)
                            .gt(AccountEntity::getExpireTime, GameDayClock.now())
                            .eq(AccountEntity::getDelete, 0));
                    accountList.forEach(
                            (account) -> {
                                log.info("【临期账号】: " + account.getName() + "\t" + account.getAccount());
                                var msg = "您的托管账号将于" + account.getExpireTime()
                                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + "过期，记得及时续费哦。";

                                messageService.push(account, "【明日方舟】托管续费提醒", msg);
                            }
                    );
                },
                triggerContext -> new CronTrigger("0 0 20 * * ?").nextExecutionTime(triggerContext)
        );
        //账号冻结检测
        taskRegistrar.addTriggerTask(
                () -> {
                    log.info("【审判庭】 账号冻结检测");
                    var accountList = accountMapper.selectList(Wrappers.<AccountEntity>lambdaQuery()
                            .gt(AccountEntity::getExpireTime, GameDayClock.now())
                            .eq(AccountEntity::getFreeze, 1)
                            .eq(AccountEntity::getDelete, 0));
                    accountList.forEach(
                            (account) -> {
                                log.info("【冻结账号】: " + account.getName() + "\t" + account.getAccount());
                                var msg = "您的账号仍处于冻结状态，若非手动冻结请及时检查账号状态，避免浪费账号托管时长";

                                messageService.push(account, "【明日方舟】账号冻结提醒", msg);

                            }
                    );
                },
                triggerContext -> new CronTrigger("0 0 20 * * ?").nextExecutionTime(triggerContext)
        );
        //每日刷新次数更新
        taskRegistrar.addTriggerTask(
                () -> {
                    log.info("【审判庭】 每日刷新次数更新");
                    var accountList = accountMapper.selectList(Wrappers.<AccountEntity>lambdaQuery()
                            .le(AccountEntity::getRefresh, 0)
                            .eq(AccountEntity::getDelete, 0)
                            .ge(AccountEntity::getExpireTime, GameDayClock.now())
                    );
                    accountList.forEach(
                            (account) -> {
                                account.setRefresh(1);
                                accountMapper.updateById(account);
                            }
                    );
                },
                triggerContext -> new CronTrigger("0 0 4 * * ?",
                        TimeZone.getTimeZone(GameDayClock.ZONE_ID)).nextExecutionTime(triggerContext)
        );
        //游戏日有效日志巡检：每天04:00切日，之后每小时检查一次，避免用错误的自然日判断。
        taskRegistrar.addTriggerTask(
                () -> submitMissingLogCheck(GameDayClock.now()),
                triggerContext -> new CronTrigger("0 10 * * * ?",
                        TimeZone.getTimeZone(GameDayClock.ZONE_ID)).nextExecutionTime(triggerContext)
        );
        //动态设备管理
        taskRegistrar.addTriggerTask(
                () -> {
                    if (!enableAutoDeviceManage) {
                        return;
                    }
                    log.info("【审判庭】 动态设备增加");
                    var payedUserList = accountMapper.selectList(Wrappers.<AccountEntity>lambdaQuery()
                            .ge(AccountEntity::getExpireTime, GameDayClock.now())
                            .eq(AccountEntity::getDelete, 0));
                    var deviceList = deviceMapper.selectList(Wrappers.<DeviceEntity>lambdaQuery()
                            .eq(DeviceEntity::getDelete, 0));
                    if (deviceList.size() < payedUserList.size() / maxPlayerInDevice) {
                        var newDevice = chinacService.createDevice(
                                "cn-jsha-cloudphone-3",
                                "805321",
                                "PREPAID",
                                0,
                                1,
                                null, null, null);
                        if (newDevice == null) {
                            messageService.pushAdmin("[审判庭] 设备增加失败提醒", "设备增加失败，请检查平台余额是否充足");
                            return;
                        }
                        SimpleDateFormat format = new SimpleDateFormat("MM_dd");
                        String time = format.format(new Date().getTime());
                        deviceMapper.insert(new DeviceEntity()
                                .setDeviceName("审判庭_" + time)
                                .setRegion("")
                                .setDeviceToken(newDevice.get(0))
                                .setDelete(0)
                        );
                        String text = "激活用户数量: " + payedUserList.size() + "\n" +
                                "设备数量: " + deviceList.size() + "\n" +
                                "已为您自动增添新设备，请留意扣费信息";
                        messageService.pushAdmin("[审判庭] 设备增加提醒", text);
                    }
                    log.info("【审判庭】 设备自动续费");

                    //检测多余设备跳过续费 最多允许冗余设备数量: 2
                    var overNum = (payedUserList.size() - deviceList.size() * maxPlayerInDevice) / maxPlayerInDevice;
                    //过滤手动添加设备
                    deviceList.removeIf(device -> device.getChinac() != 1);
                    if (overNum > 2) {
                        for (int i = 0; i < overNum; i++) {
                            Iterator<DeviceEntity> iterator = deviceList.iterator();
                            var flagDevice = iterator.next();
                            while (iterator.hasNext()) {
                                var device = iterator.next();
                                if (flagDevice.getExpireTime().isBefore(device.getExpireTime())) {
                                    flagDevice = device;
                                }
                            }
                            deviceList.remove(flagDevice);
                        }
                    }
                    for (DeviceEntity device : deviceList) {
                        if (device.getExpireTime().isBefore(GameDayClock.now().plusDays(7)) && device.getChinac() == 1) {
                            if (chinacService.renewDevice(device.getRegion(), device.getDeviceToken(), 1)) {
                                String text = "续费设备: " + device.getDeviceName() + "\n" +
                                        "已为您自动续费，请留意扣费信息";
                                messageService.pushAdmin("[审判庭] 设备续费提醒", text);
                            } else {
                                String text = "自动续费失败，请检查平台余额是否充足";
                                messageService.pushAdmin("[审判庭] 设备续费失败提醒", text);
                            }
                            break;
                        }
                    }
                },
                triggerContext -> new CronTrigger("0 0 20 * * ?").nextExecutionTime(triggerContext)
        );
        //管理员状态汇总
        taskRegistrar.addTriggerTask(
                this::sendAdminSummary,
                triggerContext -> new CronTrigger("0 * * * * ?").nextExecutionTime(triggerContext)
        );
        //异常账号检测
        taskRegistrar.addTriggerTask(
                () -> {
                    log.info("【异常账号检测】 检测开始");
                    var accountList = accountMapper.selectList(Wrappers.<AccountEntity>lambdaQuery()
                            .eq(AccountEntity::getFreeze, 0)
                            .eq(AccountEntity::getDelete, 0)
                            .ge(AccountEntity::getExpireTime, GameDayClock.now())
                    );
                    accountList.forEach(
                            (account) -> {
                                if (!dynamicInfo.getUserSanInfoMap().containsKey(account.getId())) {
                                    log.info("【异常账号检测】 异常账号: " + account.getAccount() + " " + account.getAccount());
                                    dynamicInfo.setUserSan(account.getId(), 135, 135);
                                }
                            }
                    );
                    log.info("【异常账号检测】 已完成所有异常账号自动检修");
                },
                triggerContext -> new CronTrigger("0 0 4 * * ?").nextExecutionTime(triggerContext)
        );
        //14点检查当前游戏日仍未成功登录的日常账号，并将其安全提升到等待队列前部。
        taskRegistrar.addTriggerTask(
                () -> runDailyLoginSweep(GameDayClock.now()),
                triggerContext -> new CronTrigger("0 0 14 * * ?",
                        TimeZone.getTimeZone(GameDayClock.ZONE_ID)).nextExecutionTime(triggerContext)
        );
    }

    void runDailyLoginSweep(LocalDateTime now) {
        try {
            dailyLoginSweepService.runIfDue(now);
        } catch (RuntimeException exception) {
            log.warn("\u3010\u5ba1\u5224\u5ead\u301114\u70b9\u8865\u767b\u626b\u63cf\u5931\u8d25", exception);
        }
    }

    void submitMissingLogCheck(LocalDateTime now) {
        if (!missingLogScanInFlight.compareAndSet(false, true)) {
            return;
        }
        try {
            missingLogExecutor.execute(() -> {
                try {
                    var result = accountRuntimeService.checkMissingLogs(now);
                    if (!result.getMissingAccounts().isEmpty()) {
                        log.warn("【审判庭】满9小时未检测到有效游戏日志账号数: {}",
                                result.getMissingAccounts().size());
                    }
                } catch (Exception exception) {
                    log.warn("【审判庭】游戏日志巡检失败", exception);
                } finally {
                    missingLogScanInFlight.set(false);
                }
            });
        } catch (RuntimeException exception) {
            missingLogScanInFlight.set(false);
            log.warn("【审判庭】游戏日志巡检提交失败", exception);
        }
    }

    @PreDestroy
    void shutdownMissingLogExecutor() {
        if (missingLogExecutor instanceof ExecutorService) {
            ((ExecutorService) missingLogExecutor).shutdownNow();
        }
    }

    void notifyLongRunningTasks(LocalDateTime now) {
        var assignments = taskAssignmentService.findLongRunning(now, 120);
        if (assignments.isEmpty()) {
            return;
        }
        var adminItems = new ArrayList<String>();
        for (var assignment : assignments) {
            if (!taskAssignmentService.markLongTaskNotified(assignment)) {
                continue;
            }
            var account = accountMapper.selectById(assignment.getAccountId());
            if (account == null) {
                continue;
            }
            var elapsedMinutes = Math.max(0, Duration.between(assignment.getAssignedAt(), now).toMinutes());
            messageService.push(account, "任务运行时间较长",
                    "当前任务已运行约 " + elapsedMinutes + " 分钟，已达到2小时上限；系统将自动回收并重新排队，请检查设备是否卡住。");
            adminItems.add(account.getName() + " / 设备 " + assignment.getDeviceToken()
                    + "（已运行 " + elapsedMinutes + " 分钟）");
        }
        if (!adminItems.isEmpty()) {
            messageService.pushAdmin("[审判庭] 任务占用过久",
                    "以下任务已达到或超过2小时：\n" + String.join("\n", adminItems));
        }
    }

    private void sendAdminSummary() {
        var now = GameDayClock.now().withSecond(0).withNano(0);
        var targetAdmins = new ArrayList<>(adminMapper.selectList(Wrappers.<AdminEntity>lambdaQuery()
                .and(wrapper -> wrapper.eq(AdminEntity::getDelete, 0)
                        .or()
                        .isNull(AdminEntity::getDelete))));
        targetAdmins.removeIf(admin -> !AdminNoticeConfigUtils.matchesSchedule(parseAdminNoticeConfig(admin.getNotice()).getSummarySchedule(), now.toLocalTime()));
        sendAdminSummaryNow(targetAdmins, now);
    }

    public void sendAdminSummaryNow(ArrayList<AdminEntity> targetAdmins) {
        sendAdminSummaryNow(targetAdmins, GameDayClock.now().withSecond(0).withNano(0));
    }

    private void sendAdminSummaryNow(ArrayList<AdminEntity> targetAdmins, LocalDateTime now) {
        if (targetAdmins == null || targetAdmins.isEmpty()) {
            return;
        }

        var offlineDevices = new ArrayList<>(deviceMapper.selectList(Wrappers.<DeviceEntity>lambdaQuery()
                .eq(DeviceEntity::getDelete, 0)));
        offlineDevices.removeIf(device -> !ImportantDevicePolicy.includes(device)
                || !isRecentlyOfflineDevice(device, now));

        var frozenAccounts = new ArrayList<>(accountMapper.selectList(Wrappers.<AccountEntity>lambdaQuery()
                .gt(AccountEntity::getExpireTime, now)
                .eq(AccountEntity::getFreeze, 1)
                .eq(AccountEntity::getDelete, 0)));
        var expiringAccounts = new ArrayList<>(accountMapper.selectList(Wrappers.<AccountEntity>lambdaQuery()
                .lt(AccountEntity::getExpireTime, now.plusDays(7))
                .gt(AccountEntity::getExpireTime, now)
                .eq(AccountEntity::getDelete, 0)));
        var abnormalAccounts = new ArrayList<>(accountMapper.selectList(Wrappers.<AccountEntity>lambdaQuery()
                .eq(AccountEntity::getFreeze, 0)
                .eq(AccountEntity::getDelete, 0)
                .ge(AccountEntity::getExpireTime, now)));
        abnormalAccounts.removeIf(account -> dynamicInfo.getUserSanInfoMap().containsKey(account.getId()));
        var topOperatorAccounts = extractRecentTopOperatorAccounts(new ArrayList<>(logMapper.selectList(
                Wrappers.<LogEntity>lambdaQuery()
                        .eq(LogEntity::getDelete, 0)
                        .like(LogEntity::getDetail, "\u9AD8\u7EA7\u8D44\u6DF1\u5E72\u5458")
                        .ge(LogEntity::getTime, now.minusHours(RECENT_TOP_OPERATOR_WINDOW_HOURS))
                        .orderByDesc(LogEntity::getTime)
        )));

        var waitAccounts = dynamicInfo.getAllWaitUserInfo();
        var waitIdSet = new LinkedHashSet<Long>();
        waitAccounts.removeIf(account -> !waitIdSet.add(account.getId()));
        var coolDownAccounts = getTempCoolDownAccounts(waitAccounts, now);

        var content = new StringBuilder();
        content.append("\u65F6\u95F4: ").append(now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n\n");
        appendDeviceSummary(content, "\uD83D\uDCF4 \u8FD1\u671F\u79BB\u7EBF\u8BBE\u5907", offlineDevices);
        appendAccountSummary(content, "\u2744\uFE0F \u51B7\u5374", coolDownAccounts);
        appendAccountSummary(content, "\uD83E\uDDCA \u51BB\u7ED3\u8D26\u53F7", frozenAccounts);
        appendJoinedSummary(content, "\u2728 \u8FD1\u671F\u9AD8\u7EA7\u8D44\u6DF1\u5E72\u5458", topOperatorAccounts.size(), "\u4E2A", topOperatorAccounts);
        appendAccountSummary(content, "\u23F0 7\u5929\u5185\u5230\u671F\u8D26\u53F7", expiringAccounts);
        appendAccountSummary(content, "\u26A0\uFE0F \u5F02\u5E38\u8D26\u53F7", abnormalAccounts);
        appendAccountSummary(content, "\uD83D\uDCE5 \u5F85\u5206\u914D\u961F\u5217", waitAccounts);
        var title = buildSummaryTitle(coolDownAccounts.size(), frozenAccounts.size(), offlineDevices.size());
        messageService.pushAdmin(targetAdmins, title, content.toString());
        logService.logInfo("Admin summary sent", "title: " + title + "\nadmins: " + targetAdmins.size());
    }

    private AdminNoticeConfigDTO parseAdminNoticeConfig(String notice) {
        return AdminNoticeConfigUtils.parse(gson, notice, false, "");
    }

    private String buildSummaryTitle(int coolDownCount, int frozenCount, int offlineCount) {
        var title = new StringBuilder("[\u5BA1\u5224\u5EAD]");
        var hasStat = false;
        if (coolDownCount > 0) {
            title.append(" \u51B7\u5374").append(coolDownCount).append("\u4E2A");
            hasStat = true;
        }
        if (frozenCount > 0) {
            title.append(" \u51BB\u7ED3").append(frozenCount).append("\u4E2A");
            hasStat = true;
        }
        if (offlineCount > 0) {
            title.append(" \u8BBE\u5907").append(offlineCount).append("\u53F0");
            hasStat = true;
        }
        if (!hasStat) {
            title.append(" \u72B6\u6001\u6C47\u603B");
        }
        return title.toString();
    }

    private ArrayList<String> extractRecentTopOperatorAccounts(ArrayList<LogEntity> logs) {
        var accountNames = new LinkedHashSet<String>();
        logs.forEach(log -> {
            var name = log.getName();
            if (name == null || name.isBlank()) {
                name = log.getAccount();
            }
            if (name != null && !name.isBlank()) {
                accountNames.add(name);
            }
        });
        return new ArrayList<>(accountNames);
    }

    private boolean isRecentlyOfflineDevice(DeviceEntity device, LocalDateTime now) {
        if (dynamicInfo.getDeviceStatusMap().getOrDefault(device.getDeviceToken(), 0) != 0) {
            return false;
        }
        var lastHeartbeatTime = dynamicInfo.getDeviceLastHeartbeatMap().get(device.getDeviceToken());
        return lastHeartbeatTime != null && !lastHeartbeatTime.isBefore(now.minusHours(RECENT_OFFLINE_WINDOW_HOURS));
    }

    private void appendDeviceSummary(StringBuilder content, String title, ArrayList<DeviceEntity> devices) {
        var deviceNames = new ArrayList<String>();
        devices.forEach(device -> deviceNames.add("\u8BBE\u5907 " + device.getDeviceName()));
        appendJoinedSummary(content, title, devices.size(), "\u53F0", deviceNames);
    }

    private ArrayList<AccountEntity> getTempCoolDownAccounts(ArrayList<AccountEntity> waitAccounts, LocalDateTime now) {
        var coolDownIds = new LinkedHashSet<Long>();
        waitAccounts.forEach(account -> {
            var freezeUntil = dynamicInfo.getFreezeUserInfoMap().get(account.getId());
            if (freezeUntil != null && freezeUntil.isAfter(now)) {
                coolDownIds.add(account.getId());
            }
        });
        dynamicInfo.getFreezeUserInfoMap().forEach((accountId, freezeUntil) -> {
            if (freezeUntil != null && freezeUntil.isAfter(now)) {
                coolDownIds.add(accountId);
            }
        });
        if (coolDownIds.isEmpty()) {
            return new ArrayList<>();
        }
        var accountMap = new java.util.HashMap<Long, AccountEntity>();
        accountMapper.selectBatchIds(coolDownIds).forEach(account -> {
            if ((account.getDelete() == null || account.getDelete() == 0)
                    && (account.getExpireTime() == null || account.getExpireTime().isAfter(now))) {
                accountMap.put(account.getId(), account);
            }
        });
        var coolDownAccounts = new ArrayList<AccountEntity>();
        coolDownIds.forEach(accountId -> {
            var account = accountMap.get(accountId);
            if (account != null) {
                coolDownAccounts.add(account);
            }
        });
        return coolDownAccounts;
    }

    private void appendAccountSummary(StringBuilder content, String title, ArrayList<AccountEntity> accounts) {
        var accountNames = new ArrayList<String>();
        accounts.forEach(account -> accountNames.add(account.getName()));
        appendJoinedSummary(content, title, accounts.size(), "\u4E2A", accountNames);
    }

    private void appendJoinedSummary(StringBuilder content, String title, int size, String unit, ArrayList<String> names) {
        if (size <= 0) {
            return;
        }
        content.append(title).append(" ").append(size).append(unit).append("\n");
        if (!names.isEmpty()) {
            content.append(String.join(" / ", names)).append("\n\n");
        } else {
            content.append("\n");
        }
    }
}
