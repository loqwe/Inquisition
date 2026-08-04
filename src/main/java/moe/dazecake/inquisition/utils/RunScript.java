package moe.dazecake.inquisition.utils;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import moe.dazecake.inquisition.mapper.AccountMapper;
import moe.dazecake.inquisition.mapper.AdminMapper;
import moe.dazecake.inquisition.mapper.DeviceMapper;
import moe.dazecake.inquisition.model.dto.chinac.ChinacPhoneEntity;
import moe.dazecake.inquisition.model.entity.AccountEntity;
import moe.dazecake.inquisition.model.entity.AdminEntity;
import moe.dazecake.inquisition.model.entity.DeviceEntity;
import moe.dazecake.inquisition.service.impl.ChinacServiceImpl;
import moe.dazecake.inquisition.service.impl.DailyLoginSweepService;
import moe.dazecake.inquisition.service.impl.FinalLoginSweepService;
import moe.dazecake.inquisition.service.impl.DeviceRuntimeService;
import moe.dazecake.inquisition.service.impl.AccountRuntimeService;
import moe.dazecake.inquisition.service.impl.AccountScheduledDispatchService;
import moe.dazecake.inquisition.service.impl.DispatchQueueService;
import moe.dazecake.inquisition.service.impl.PartialScheduledDispatchException;
import moe.dazecake.inquisition.service.impl.ScheduledTaskMonitorService;
import moe.dazecake.inquisition.service.impl.TaskAssignmentService;
import moe.dazecake.inquisition.service.impl.UrgentTaskService;
import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.io.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static moe.dazecake.inquisition.utils.JWTUtils.SECRET;

@Component
@Slf4j
public class RunScript implements ApplicationRunner {

    @Resource
    DynamicInfo dynamicInfo;

    @Resource
    AccountMapper accountMapper;

    @Resource
    DeviceMapper deviceMapper;

    @Resource
    AdminMapper adminMapper;

    @Resource
    ChinacServiceImpl chinacService;

    @Resource
    TaskAssignmentService taskAssignmentService;

    @Resource
    DeviceRuntimeService deviceRuntimeService;

    @Resource
    AccountRuntimeService accountRuntimeService;

    @Resource
    DailyLoginSweepService dailyLoginSweepService;

    @Resource
    FinalLoginSweepService finalLoginSweepService;

    @Resource
    ScheduledTaskMonitorService scheduledTaskMonitor;

    @Resource
    UrgentTaskService urgentTaskService;

    @Resource
    AccountScheduledDispatchService accountScheduledDispatchService;

    @Resource
    DispatchQueueService dispatchQueueService;

    @Value("${inquisition.secret:}")
    String secret;

    @Value("${inquisition.chinac.enableAutoDeviceManage:false}")
    boolean enableAutoDeviceManage;

    @Value("${inquisition.accountSchedule.enabled:false}")
    boolean enableAccountSchedule;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        log.info("【审判庭初始化】 执行中...");
        File file = new File(System.getProperty("user.dir") + File.separator + "config" + File.separator + "data.json");
        if (file.exists()) {
            log.info("【审判庭初始化】 检测到数据文件，正在读取...");
            Gson gson = new Gson();
            dynamicInfo.load(gson.fromJson(new BufferedReader(new FileReader(file)), MemoryInfo.class));

            log.info("【审判庭初始化】 读取完成");
        } else {
            log.info("【审判庭初始化】 未检测到数据文件，正在初始化...");
            //检查admin表是否有数据
            List<AdminEntity> adminEntities = adminMapper.selectList(null);
            if (adminEntities.size() == 0) {
                AdminEntity adminEntity = new AdminEntity();
                adminEntity.setUsername("root");
                adminEntity.setPassword("7966fd2201810e386e8407feaf09b4ea");
                adminEntity.setPermission("root");
                adminMapper.insert(adminEntity);
                log.info("【审判庭初始化】 初始化管理员账号: root");
                log.info("【审判庭初始化】 已初始化管理员账号，请通过安全渠道设置管理员密码");
            }

            var devices = deviceMapper.selectList(
                    Wrappers.<DeviceEntity>lambdaQuery()
                            .eq(DeviceEntity::getDelete, 0)
            );
            devices.forEach(
                    device -> {
                        dynamicInfo.getDeviceStatusMap().put(device.getDeviceToken(), 0);
                        dynamicInfo.getDeviceCounterMap().put(device.getDeviceToken(), 1);
                    }
            );
            if (enableAutoDeviceManage) {
                log.info("【审判庭初始化】 同步Chinac设备");
                var chinacDeviceList = chinacService.queryAllDeviceList();
                for (ChinacPhoneEntity chinacPhone : chinacDeviceList) {
                    if (!chinacPhone.getPayType().equals("PREPAID")) {
                        continue;
                    }
                    if (deviceMapper.selectOne(Wrappers.<DeviceEntity>lambdaQuery()
                            .eq(DeviceEntity::getDeviceToken, chinacPhone.getId())) == null) {
                        var newDevice = new DeviceEntity();
                        Instant instant = Instant.ofEpochMilli(chinacPhone.getDueTime());
                        var zone = GameDayClock.ZONE_ID;
                        newDevice.setDeviceName(chinacPhone.getName())
                                .setDeviceRole(DeviceRolePolicy.BACKUP)
                                .setDeviceToken(chinacPhone.getId())
                                .setRegion(chinacPhone.getRegion())
                                .setExpireTime(LocalDateTime.ofInstant(instant, zone))
                                .setDelete(0)
                                .setChinac(1);
                        deviceMapper.insert(newDevice);
                        log.info("【审判庭初始化】 同步设备 " + newDevice.getDeviceToken());
                        dynamicInfo.getDeviceStatusMap().put(newDevice.getDeviceToken(), 0);
                        dynamicInfo.getDeviceCounterMap().put(newDevice.getDeviceToken(), 1);
                    }
                }
            }
            var dailyAccounts = accountMapper.selectList(Wrappers.<AccountEntity>lambdaQuery()
                    .eq(AccountEntity::getDelete, 0)
                    .eq(AccountEntity::getFreeze, 0)
                    .eq(AccountEntity::getTaskType, "daily")
                    .ge(AccountEntity::getExpireTime, GameDayClock.now())
            );
            for (AccountEntity account : dailyAccounts) {
                dynamicInfo.setUserSanZero(account.getId());
                dispatchQueueService.enqueueAuto(account.getId());
            }

        }
        var now = GameDayClock.now();
        // Active leases are authoritative in MySQL; do not revive stale work entries from data.json.
        dynamicInfo.getWorkUserList().clear();
        dynamicInfo.getWorkUserInfoMap().clear();
        var cleanedUrgentTasks = cleanupUrgentLoginTasks(now);
        var activeDevices = deviceMapper.selectList(Wrappers.<DeviceEntity>lambdaQuery()
                .eq(DeviceEntity::getDelete, 0));
        activeDevices.forEach(device -> deviceRuntimeService.initializeDevice(device, now));
        var expiredAssignments = taskAssignmentService.closeExpiredAssignments(now);
        var restoredAssignments = taskAssignmentService.restoreActiveAssignments(now);
        var restoredCooldowns = accountRuntimeService.restoreRetryCooldowns(now);
        var restoredUrgentTasks = restoreUrgentLoginTasks(now);
        dispatchQueueService.reconcileRestoredQueue(now, enableAccountSchedule);
        if (expiredAssignments > 0 || restoredAssignments > 0) {
            log.info("【审判庭初始化】恢复任务租约: active={}, expired={}", restoredAssignments, expiredAssignments);
        }
        if (restoredCooldowns > 0) {
            log.info("【审判庭初始化】恢复重试冷却账号数: {}", restoredCooldowns);
        }
        if (restoredUrgentTasks > 0) {
            log.info("【审判庭初始化】恢复26点加急登录账号数: {}", restoredUrgentTasks);
        }
        if (cleanedUrgentTasks > 0) {
            log.info("【审判庭初始化】清理过期26点加急登录记录数: {}", cleanedUrgentTasks);
        }

        if (secret != null && !secret.isBlank()) {
            SECRET = secret;
        } else {
            SECRET = RandomStringUtils.randomAlphabetic(16);
            log.info("【审判庭初始化】 未配置 secret，已生成进程内随机值；如需跨重启保持会话，请通过环境变量配置固定值");
        }

        try {
            runDailyLoginCatchUp(now);
        } catch (RuntimeException exception) {
            log.warn("【审判庭初始化】14点补登启动补偿失败", exception);
        }
        try {
            runFinalLoginCatchUp(now);
        } catch (RuntimeException exception) {
            log.warn("【审判庭初始化】26点最终补登启动补偿失败", exception);
        }
        try {
            runAccountScheduledDispatchCatchUp(now);
        } catch (RuntimeException exception) {
            log.warn("【审判庭初始化】账号定时调度启动补偿失败", exception);
        }

        log.info("【审判庭初始化】 初始化完成");
    }

    void runDailyLoginCatchUp(LocalDateTime now) {
        scheduledTaskMonitor.execute(DynamicScheduleTask.DAILY_LOGIN_SWEEP_TASK, "STARTUP_RECOVERY",
                () -> dailyLoginSweepService.runIfDue(now));
    }

    void runFinalLoginCatchUp(LocalDateTime now) {
        scheduledTaskMonitor.execute(DynamicScheduleTask.FINAL_LOGIN_SWEEP_TASK, "STARTUP_RECOVERY",
                () -> finalLoginSweepService.runIfDue(now));
    }

    void runAccountScheduledDispatchCatchUp(LocalDateTime now) {
        if (!enableAccountSchedule) {
            return;
        }
        var restored = accountScheduledDispatchService.restoreDispatchable(now);
        dispatchQueueService.enqueueScheduledRuns(restored, now);
        var scanned = new int[1];
        scheduledTaskMonitor.execute(DynamicScheduleTask.ACCOUNT_SCHEDULED_DISPATCH_TASK,
                "STARTUP_RECOVERY", () -> scanned[0] = scanAndEnqueueScheduled(now));
        if (!restored.isEmpty() || scanned[0] > 0) {
            log.info("【审判庭初始化】定时运行恢复: restored={}, dispatchable={}",
                    restored.size(), scanned[0]);
        }
    }

    private int scanAndEnqueueScheduled(LocalDateTime now) {
        try {
            var runs = accountScheduledDispatchService.scan(now);
            dispatchQueueService.enqueueScheduledRuns(runs, now);
            return runs.size();
        } catch (PartialScheduledDispatchException exception) {
            dispatchQueueService.enqueueScheduledRuns(exception.getDispatchableRuns(), now);
            throw exception;
        }
    }

    int cleanupUrgentLoginTasks(LocalDateTime now) {
        return finalLoginSweepService.cleanup(now);
    }

    int restoreUrgentLoginTasks(LocalDateTime now) {
        var restored = 0;
        for (var task : urgentTaskService.findActiveForGameDay(GameDayClock.gameDay(now))) {
            if (task == null || task.getAccountId() == null) {
                continue;
            }
            if (UrgentTaskService.STATUS_RUNNING.equals(task.getStatus())) {
                if (taskAssignmentService.findByAccount(task.getAccountId()).isPresent()) {
                    continue;
                }
                if (!urgentTaskService.markWaiting(task.getId(), now)) {
                    continue;
                }
            }
            if (task.getNextRetryAt() != null && task.getNextRetryAt().isAfter(now)) {
                dynamicInfo.getFreezeUserInfoMap().put(task.getAccountId(), task.getNextRetryAt());
                dynamicInfo.getCooldownReasonMap().put(task.getAccountId(), "retryBackoff");
            }
            dispatchQueueService.restoreBest(task.getAccountId(), now);
            restored++;
        }
        return restored;
    }

    @PreDestroy
    public void destroy() {
        log.info("【审判庭关闭】 正在保存数据...");
        Gson gson = new Gson();
        String str = gson.toJson(dynamicInfo.dump());
        try {
            var printWriter = new PrintWriter(System.getProperty("user.dir") + File.separator + "config" + File.separator + "data.json");
            printWriter.write(str);
            printWriter.close();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        log.info("【审判庭关闭】 数据保存完毕");
        log.info("【审判庭关闭】 服务端已正常关闭");
    }
}
