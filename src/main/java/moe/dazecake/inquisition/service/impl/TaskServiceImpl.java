package moe.dazecake.inquisition.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import moe.dazecake.inquisition.constant.enums.TaskType;
import moe.dazecake.inquisition.mapper.AccountMapper;
import moe.dazecake.inquisition.mapper.DeviceMapper;
import moe.dazecake.inquisition.mapper.mapstruct.AccountConvert;
import moe.dazecake.inquisition.model.dto.account.AccountDTO;
import moe.dazecake.inquisition.model.dto.log.AddLogDTO;
import moe.dazecake.inquisition.model.entity.AccountEntity;
import moe.dazecake.inquisition.model.entity.DeviceEntity;
import moe.dazecake.inquisition.model.entity.TaskAssignmentEntity;
import moe.dazecake.inquisition.model.entity.UrgentTaskEntity;
import moe.dazecake.inquisition.model.entity.ConfigEntitySet.ConfigEntity;
import moe.dazecake.inquisition.model.entity.ConfigEntitySet.Infrastructure;
import moe.dazecake.inquisition.model.entity.ConfigEntitySet.Offer;
import moe.dazecake.inquisition.model.entity.ConfigEntitySet.Sanity;
import moe.dazecake.inquisition.model.local.UserSan;
import moe.dazecake.inquisition.model.local.DispatchIntent;
import moe.dazecake.inquisition.model.vo.account.AccountCooldownVO;
import moe.dazecake.inquisition.service.intf.TaskService;
import moe.dazecake.inquisition.utils.DailyPlanUtil;
import moe.dazecake.inquisition.utils.DeviceScopeUtil;
import moe.dazecake.inquisition.utils.DeviceRolePolicy;
import moe.dazecake.inquisition.utils.DynamicInfo;
import moe.dazecake.inquisition.utils.GameDayClock;
import moe.dazecake.inquisition.utils.Result;
import moe.dazecake.inquisition.utils.TimeUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
public class TaskServiceImpl implements TaskService {

    @Resource
    DeviceMapper deviceMapper;

    @Resource
    DynamicInfo dynamicInfo;

    @Resource
    LogServiceImpl logService;

    @Resource
    MessageServiceImpl messageService;

    @Resource
    HttpServiceImpl httpService;

    @Resource
    AccountMapper accountMapper;

    @Resource
    TaskAssignmentService taskAssignmentService;

    @Resource
    DeviceRuntimeService deviceRuntimeService;

    @Resource
    AccountRuntimeService accountRuntimeService;

    @Resource
    SanityOcrService sanityOcrService;

    @Resource
    UrgentTaskService urgentTaskService;

    @Resource
    DispatchQueueService dispatchQueueService;

    @Resource
    AccountScheduledRunLifecycleService scheduledLifecycleService;

    @Value("${spring.mail.enable:false}")
    boolean enableMail;

    @Value("${spring.mail.to:}")
    String to;

    @Value("${wx-pusher.enable:false}")
    boolean enableWxPusher;

    AccountDTO buildTaskAccountDTO(AccountEntity account, TaskAssignmentEntity assignment) {
        var dto = AccountConvert.INSTANCE.toAccountDTO(account);
        dto.setAssignmentId(assignment == null ? null : assignment.getAssignmentId());
        if (assignment != null && UrgentTaskService.MODE_LOGIN_ONLY.equals(assignment.getTaskMode())) {
            dto.setTaskType("daily");
            dto.setConfig(loginOnlyConfig());
        } else {
            DailyPlanUtil.normalizeDailyPlan(dto);
            DailyPlanUtil.compileDailyPlanForDevice(dto);
        }
        return dto;
    }

    private ConfigEntity loginOnlyConfig() {
        var config = new ConfigEntity();
        var daily = config.getDaily();
        daily.setFight(new ArrayList<>());
        daily.setPlan(new ArrayList<>());
        daily.setSanity(new Sanity(0, 0));
        daily.setMail(false);
        daily.setFriend(false);
        daily.setInfrastructure(new Infrastructure(false, false, false, false, false));
        daily.setCredit(false);
        daily.setOffer(new Offer(false, false, false, false, false, false));
        daily.setTask(false);
        daily.setActivity(false);
        daily.setShopping(false);
        return config;
    }

    Map<Long, UrgentTaskEntity> promoteReadyUrgentTasks(LocalDateTime now) {
        var urgentByAccount = new LinkedHashMap<Long, UrgentTaskEntity>();
        urgentTaskService.findDispatchable(GameDayClock.gameDay(now), now).forEach(task -> {
            if (task != null && task.getAccountId() != null) {
                urgentByAccount.putIfAbsent(task.getAccountId(), task);
            }
        });
        urgentByAccount.keySet().forEach(accountId ->
                dispatchQueueService.enqueueUrgent(accountId, now));
        return urgentByAccount;
    }

    private boolean isDeleted(AccountEntity account) {
        return account == null || Objects.equals(account.getDelete(), 1);
    }

    private String cooldownReasonMessage(String type) {
        if (type == null || type.isBlank()) {
            return "未知异常，临时冷却后自动重试";
        }
        switch (type) {
            case "lineBusy":
                return "线路繁忙或设备资源冲突，临时冷却后自动重试";
            case "accountError":
                return "账号登录异常，系统已复核账号状态";
            case "biliLoginLimit":
                return "B服近期登录设备较多，疑似触发登录限制";
            case "manual":
                return "管理员手动设置临时冷却";
            case "retryBackoff":
                return "任务连续失败，系统按递增间隔自动重试";
            case "deviceRepeatedFailure":
                return "设备连续失败，暂停1小时后自动重试";
            case "deviceOffline":
                return "设备离线，任务回收后等待重新分配";
            default:
                return "任务失败: " + type + "，临时冷却后自动重试";
        }
    }

    private void addWaitTaskIfAbsent(Long id) {
        dispatchQueueService.restoreBest(id, GameDayClock.now());
    }

    private void putAccountOnCooldown(AccountEntity account, String deviceToken, String reason,
                                      LocalDateTime cooldownUntil, boolean requeue, boolean notifyAdmin) {
        dynamicInfo.getFreezeUserInfoMap().put(account.getId(), cooldownUntil);
        dynamicInfo.getCooldownReasonMap().put(account.getId(), reason);
        if (requeue) {
            addWaitTaskIfAbsent(account.getId());
        }

        var untilText = cooldownUntil.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        var message = "账号 " + account.getName() + "(" + account.getAccount() + ") 已进入临时冷却；原因: "
                + reason + " / " + cooldownReasonMessage(reason) + "；冷却至: " + untilText
                + "；冷却期间调度器会跳过该账号，到期后自动回到队列。";
        log(deviceToken == null ? "SYSTEM" : deviceToken, account, "WARN", "账号临时冷却", message, null);
        logService.logWarn("[审判庭] 账号临时冷却", message);
        // 临时冷却只保留账号日志和系统日志，避免重复推送管理员通知。
    }


    @Override
    public Result<AccountDTO> getTask(String deviceToken) {

        restoreExpiredCooldownTasks();

        if (!dynamicInfo.getActive()) {
            return Result.failed("审判庭暂停任务授权中");
        }

        synchronized (dynamicInfo.getHaltList()) {
            if (dynamicInfo.getHaltList().contains(deviceToken)) {
                return Result.failed(500, "设备等待停机确认，暂不分配新任务");
            }
        }

        //设备合法性检查
        var device = deviceMapper.selectOne(Wrappers.<DeviceEntity>lambdaQuery()
                .eq(DeviceEntity::getDeviceToken, deviceToken)
                .eq(DeviceEntity::getDelete, 0));
        if (device == null) {
            return Result.unauthorized("设备未授权");
        }
        var now = GameDayClock.now();
        if (!deviceRuntimeService.hasFreshHeartbeat(deviceToken, now)) {
            return Result.failed(429, "设备心跳已过期，暂不分配任务");
        }
        if (deviceRuntimeService.isSuspended(deviceToken, now)) {
            return Result.failed(429, "设备连续失败，已暂停任务分配1小时");
        }

        //重复请求检查
        var existingAssignment = taskAssignmentService.findByDevice(deviceToken).orElse(null);
        if (existingAssignment != null) {
            if (existingAssignment.getLeaseExpiresAt() != null
                    && !existingAssignment.getLeaseExpiresAt().isAfter(GameDayClock.now())) {
                taskAssignmentService.closeAssignment(existingAssignment, "TIMED_OUT", "two hour limit", true);
                existingAssignment = null;
            }
        }
        if (existingAssignment != null) {
            var existingAccount = accountMapper.selectById(existingAssignment.getAccountId());
            if (!isDeleted(existingAccount)) {
                return Result.repeatSuccess(
                        buildTaskAccountDTO(existingAccount, existingAssignment), "重复获取");
            }
            taskAssignmentService.closeAssignment(existingAssignment, "INVALID", "account no longer exists", false);
        }

        if (DeviceRolePolicy.isBackup(device) && hasOnlineImportantDevice(deviceToken, now)) {
            return Result.success("重点设备在线，备用设备待命");
        }

        var urgentByAccount = promoteReadyUrgentTasks(now);
        //任务上锁
        var waitingSnapshot = dispatchQueueService.snapshot();
        if (!waitingSnapshot.isEmpty()) {
            AccountEntity account = null;
            UrgentTaskEntity selectedUrgentTask = null;
            DispatchIntent selectedIntent = null;

            //检查任务是否达到下发标准
            var hit = false;
            for (Long accountId : waitingSnapshot) {
                account = accountMapper.selectById(accountId);

                //删除检查
                if (account == null) {
                    dispatchQueueService.remove(accountId);
                    continue;
                }
                if (isDeleted(account) || account.getExpireTime() == null
                        || account.getExpireTime().isBefore(now)) {
                    dynamicInfo.getUserSanInfoMap().remove(account.getId());
                    dynamicInfo.getFreezeUserInfoMap().remove(account.getId());
                    dynamicInfo.getCooldownReasonMap().remove(account.getId());
                    dispatchQueueService.remove(account.getId());
                    continue;
                }

                var candidateIntent = dispatchQueueService.resolve(account.getId(), now);
                if (candidateIntent == null) {
                    dispatchQueueService.remove(account.getId());
                    continue;
                }

                //作用域检查
                if (!DeviceScopeUtil.supports(device, account.getTaskType())) {
                    continue;
                } else {
                    //B服日常任务不分配至特殊任务设备
                    if (account.getServer() == 1 && account.getTaskType().equals("daily") && !DeviceScopeUtil.supports(device, "b_daily")) {
                        continue;
                    }
                }

                //时间检查，不在激活区间则跳转到下一个判断
                var candidateUrgentTask = DispatchIntent.SOURCE_URGENT_26.equals(candidateIntent.getSource())
                        ? urgentByAccount.get(account.getId()) : null;
                var ignoresActivationTime = DispatchIntent.SOURCE_URGENT_26.equals(candidateIntent.getSource())
                        || DispatchIntent.SOURCE_SCHEDULED.equals(candidateIntent.getSource());
                if (!ignoresActivationTime && !checkActivationTime(account)) {
                    continue;
                }

                //B服限制检查
                if (account.getServer() == 1 && account.getBLimitDevice().size() != 0 && account.getTaskType().equals("daily")) {
                    var usedDeviceToken = account.getBLimitDevice().get(0);
                    if (!deviceToken.equals(usedDeviceToken)) {
                        if (dynamicInfo.getDeviceStatusMap().containsKey(usedDeviceToken)) {
                            continue;
                        } else {
                            account.getBLimitDevice().clear();
                            accountMapper.updateById(account);
                        }
                    }
                }

                //重复分配任务检查
                AccountEntity finalAccount = account;
                if (dynamicInfo.getWorkUserList().stream()
                        .anyMatch(worker -> worker.equals(finalAccount.getId()))) {
                    dispatchQueueService.remove(account.getId());
                    continue;
                }

                //冻结判断，不处于冻结状态则返回任务
                if (!checkFreeze(account)) {
                    selectedUrgentTask = candidateUrgentTask;
                    selectedIntent = candidateIntent;
                    hit = true;
                    break;
                }
            }

            //检查是已经遍历完整个列表
            if (!hit) {
                //没有可用的任务
                return Result.success("没有可用的任务");
            }

            //任务上锁，同时分配强制超时期限
            var assignment = lockTask(deviceToken, account, selectedUrgentTask, selectedIntent);

            //记录日志
//            log(deviceToken, account, "INFO", "任务开始", "任务开始", null);

            //推送消息
            messageService.push(account, "任务开始", "请勿强行顶号，强行顶号将导致轮空");

            //移出等待队列
            dispatchQueueService.dequeue(selectedIntent);

            //理智归零
            dynamicInfo.setUserSanZero(account.getId());


            return Result.success(buildTaskAccountDTO(account, assignment), "获取成功");

        } else {
            return Result.success("待分配队列为空");
        }
    }

    private boolean hasOnlineImportantDevice(String excludedToken, LocalDateTime now) {
        return deviceMapper.selectList(Wrappers.<DeviceEntity>lambdaQuery()
                        .eq(DeviceEntity::getDelete, 0))
                .stream()
                .filter(DeviceRolePolicy::isImportant)
                .filter(device -> !Objects.equals(device.getDeviceToken(), excludedToken))
                .anyMatch(device -> deviceRuntimeService.hasFreshHeartbeat(device.getDeviceToken(), now));
    }

    @Override
    public Result<String> completeTask(String deviceToken, String assignmentId, String imageUrl) {
        var assignment = taskAssignmentService.findByDevice(deviceToken).orElse(null);
        if (!taskAssignmentService.matchesSubmission(assignment, deviceToken, assignmentId)) {
            return Result.failed(409, "任务分配已失效，请停止当前任务并重新获取");
        }

        var account = accountMapper.selectById(assignment.getAccountId());
        if (account == null) {
            taskAssignmentService.closeAssignment(assignment, "INVALID", "account no longer exists", false);
            return Result.notFound("任务账号不存在");
        }

        if (UrgentTaskService.MODE_LOGIN_ONLY.equals(assignment.getTaskMode())) {
            var activeUrgency = urgentTaskService.findActiveByAccount(
                    account.getId(), GameDayClock.gameDay(GameDayClock.now()));
            if (activeUrgency.isPresent()) {
                if (!taskAssignmentService.closeAssignment(assignment, "FAILED",
                        "login-only completed without successful login", false)) {
                    return Result.failed("补登状态保存失败，请稍后重试");
                }
                var failedAt = GameDayClock.now();
                var retryUntil = accountRuntimeService.recordFailure(
                        account, deviceToken, "LOGIN_NOT_CONFIRMED", failedAt);
                dynamicInfo.getFreezeUserInfoMap().put(account.getId(), retryUntil);
                dynamicInfo.getCooldownReasonMap().put(account.getId(), "retryBackoff");
                urgentTaskService.markRetry(activeUrgency.get(), "LOGIN_NOT_CONFIRMED", retryUntil, failedAt);
                addWaitTaskIfAbsent(account.getId());
                return Result.success("登录成功日志未确认，已进入递增重试");
            }
            if (!taskAssignmentService.closeAssignment(assignment, "STALE_LOGIN_ONLY",
                    "urgent game day ended", true)) {
                return Result.failed("补登状态保存失败，请稍后重试");
            }
            return Result.success("补登窗口已结束，账号已回到普通队列");
        }

        //检查B服限制新增设备
        if (account.getServer() == 1 && account.getBLimitDevice().size() == 0) {
            account.getBLimitDevice().add(deviceToken);
            accountMapper.updateById(account);
        }

        //记录日志
//        log(deviceToken, account, "INFO", "任务完成", "请查看上一条日志以查看状态", imageUrl);

        var taskType = TaskType.getByStr(account.getTaskType());
        //推送消息
//        switch (TaskType.getByStr(account.getTaskType())) {
//            case DAILY:
//                messageService.push(account, "每日任务完成", "任务完成，可登陆面板查看作战结果\n" + "<img src=\"" + imageUrl + "\" alt=\"screenshots\">");
//                break;
//            case ROGUE:
//            case ROGUE2:
//                messageService.pushAdmin("肉鸽任务完成", "用户: " + account.getName() + " 肉鸽任务已完成\n" + "<img src=\"" + imageUrl + "\" alt=\"screenshots\">");
//                messageService.push(account, "肉鸽任务完成", "肉鸽任务已完成，可登陆面板查看作战结果\n" + "<img src=\"" + imageUrl + "\" alt=\"screenshots\">");
//                //恢复日常任务
//                account.setTaskType("daily");
//                accountMapper.updateById(account);
//                break;
//            case SAND_FIRE:
//                messageService.pushAdmin("生息演算任务完成", "用户: " + account.getName() + " 生息演算任务已完成\n" + "<img src=\"" + imageUrl + "\" alt=\"screenshots\">");
//                messageService.push(account, "生息演算任务完成", "生息演算任务已完成，可登陆面板查看作战结果\n" + "<img src=\"" + imageUrl + "\" alt=\"screenshots\">");
//                //恢复日常任务
//                account.setTaskType("daily");
//                accountMapper.updateById(account);
//                break;
//        }
        messageService.push(account, taskType.getName() + "任务完成", taskType.getName() + "任务完成，可登陆面板查看作战结果\n" + "<img src=\"" + imageUrl + "\" alt=\"screenshots\">");
        if (taskType != TaskType.DAILY) {
            account.setTaskType(TaskType.DAILY.getType());
            accountMapper.updateById(account);
        }

        if (!taskAssignmentService.closeAssignment(
                assignment, "COMPLETED", "device reported completion", false)) {
            return Result.failed("任务完成状态保存失败，请稍后重试");
        }
        var completedAt = GameDayClock.now();
        deviceRuntimeService.recordTaskSuccess(deviceToken, completedAt);
        accountRuntimeService.recordTaskCompleted(account.getId(), completedAt);
        sanityOcrService.submit(account.getId(), imageUrl, completedAt);
        var activeUrgency = urgentTaskService.findActiveByAccount(
                account.getId(), GameDayClock.gameDay(completedAt));
        if (activeUrgency.isPresent()) {
            var urgentTask = activeUrgency.get();
            if (UrgentTaskService.STATUS_RUNNING.equals(urgentTask.getStatus())) {
                urgentTaskService.markWaiting(urgentTask.getId(), completedAt);
            }
            addWaitTaskIfAbsent(account.getId());
        }

        return Result.success("success");
    }

    @Override
    public Result<String> failTask(String deviceToken, String assignmentId, String type, String imageUrl) {
        var assignment = taskAssignmentService.findByDevice(deviceToken).orElse(null);
        if (!taskAssignmentService.matchesSubmission(assignment, deviceToken, assignmentId)) {
            return Result.failed(409, "任务分配已失效，请停止当前任务并重新获取");
        }

        var account = accountMapper.selectById(assignment.getAccountId());
        if (account == null) {
            taskAssignmentService.closeAssignment(assignment, "INVALID", "account no longer exists", false);
            return Result.notFound("任务账号不存在");
        }

        //记录日志
//        log(deviceToken, account, "WARN", "任务失败", "任务失败,请查看上一条日志检查原因: " + type, imageUrl);

        if (!taskAssignmentService.closeAssignment(
                assignment, "FAILED", type == null ? "unknown" : type, false)) {
            return Result.failed("任务失败状态保存失败，请稍后重试");
        }

        var failureAt = GameDayClock.now();
        var retryUntil = accountRuntimeService.recordFailure(
                account, deviceToken, type == null ? "unknown" : type, failureAt);

        //异常处理
        errorHandle(account, deviceToken, type, false);
        var existingCooldown = dynamicInfo.getFreezeUserInfoMap().get(account.getId());
        if (existingCooldown == null || existingCooldown.isBefore(retryUntil)) {
            dynamicInfo.getFreezeUserInfoMap().put(account.getId(), retryUntil);
            dynamicInfo.getCooldownReasonMap().put(account.getId(), "retryBackoff");
        }
        if (deviceRuntimeService.recordTaskFailure(deviceToken, failureAt)) {
            var suspendedUntil = failureAt.plusHours(1);
            dynamicInfo.getFreezeUserInfoMap().put(account.getId(), suspendedUntil);
            dynamicInfo.getCooldownReasonMap().put(account.getId(), "deviceRepeatedFailure");
            messageService.push(account, "任务保护",
                    "当前设备连续任务失败，已暂停1小时并重新排队；系统会自动换用其他可用设备。");
        }

        var activeUrgency = urgentTaskService.findActiveByAccount(
                account.getId(), GameDayClock.gameDay(failureAt));
        if (activeUrgency.isPresent()) {
            var effectiveRetryAt = dynamicInfo.getFreezeUserInfoMap().getOrDefault(account.getId(), retryUntil);
            urgentTaskService.markRetry(activeUrgency.get(), type == null ? "unknown" : type,
                    effectiveRetryAt, failureAt);
        }
        var effectiveRetryAt = dynamicInfo.getFreezeUserInfoMap().getOrDefault(account.getId(), retryUntil);
        var shouldRequeue = !DispatchIntent.SOURCE_SCHEDULED.equals(assignment.getDispatchSource())
                || scheduledLifecycleService.retry(assignment,
                type == null ? "unknown" : type, effectiveRetryAt);
        if (shouldRequeue) {
            dispatchQueueService.restoreBest(account.getId(), failureAt);
        }

        //推送消息
        messageService.push(account, "任务失败", "任务失败，请登陆面板查看失败原因");

        return Result.success("success");
    }

    @Override
    public Result<String> tempInsertTask(Long id) {
        Result<String> result = new Result<>();
        dispatchQueueService.enqueueManual(id);

        return result.setCode(200)
                .setMsg("插队成功")
                .setData(null);
    }

    @Override
    public Result<String> tempRemoveTask(Long id) {
        Result<String> result = new Result<>();

        if (dispatchQueueService.contains(id)) {
            forceHaltTask(id);
            return result.setCode(200)
                    .setMsg("成功移出队列")
                    .setData(null);
        }

        return result.setCode(404)
                .setMsg("未找到该账号")
                .setData(null);
    }

    @Override
    public Result<String> forceLoadAllTask() {
        var accountIds = accountMapper.selectList(
                        Wrappers.<AccountEntity>lambdaQuery()
                                .eq(AccountEntity::getDelete, 0)
                                .eq(AccountEntity::getFreeze, 0)
                                .eq(AccountEntity::getTaskType, "daily")
                                .ge(AccountEntity::getExpireTime, GameDayClock.now())
                ).stream().map(AccountEntity::getId).collect(Collectors.toList());
        dispatchQueueService.replaceAutos(accountIds, GameDayClock.now());

        //记录日志
        logService.logInfo("任务列表刷新", "管理员强制刷新了任务队列");

        return new Result<String>().setCode(200)
                .setMsg("载入成功")
                .setData(null);
    }

    @Override
    public Result<String> forceUnlockOneTask(String deviceToken) {
        forceHaltTask(dynamicInfo.getUserIdByDeviceToken(deviceToken));

        return new Result<String>().setCode(200)
                .setMsg("解锁成功")
                .setData(null);
    }

    @Override
    public Result<String> forceUnlockTaskList() {
        for (TaskAssignmentEntity assignment : taskAssignmentService.findAll()) {
            synchronized (dynamicInfo.getHaltList()) {
                if (!dynamicInfo.getHaltList().contains(assignment.getDeviceToken())) {
                    dynamicInfo.getHaltList().add(assignment.getDeviceToken());
                }
            }
            taskAssignmentService.closeAssignment(assignment, "REVOKED", "administrator force unlock", true);
        }
        dynamicInfo.getWorkUserList().clear();
        dynamicInfo.getWorkUserInfoMap().clear();

        //记录日志
        logService.logInfo("强制解锁", "管理员强制解锁释放整个上锁队列");

        return Result.success("强制解锁成功");
    }

    //检查是否处于时间激活区间，如果是，则返回true，否则返回false
    @Override
    public boolean checkActivationTime(AccountEntity account) {
        int dayOfWeek = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1;

        switch (dayOfWeek) {
            case 1:
                if (account.getActive().getMonday().isEnable()) {

                    if (account.getActive().getMonday().getDetail().isEmpty()) {
                        break;
                    } else {
                        //遍历非激活时间区间
                        for (int i1 = 0; i1 < account.getActive().getMonday().getDetail().size(); i1++) {
                            String[] time = account.getActive().getMonday().getDetail().get(i1).split("-");

                            //处于非激活时间内
                            if (TimeUtil.isInTime(time[0], time[1])) {
                                //不通过
                                return false;
                            }
                        }
                    }

                } else {
                    return false;
                }
                break;
            case 2:
                if (account.getActive().getTuesday().isEnable()) {

                    if (account.getActive().getTuesday().getDetail().isEmpty()) {
                        break;
                    } else {
                        //遍历非激活时间区间
                        for (int i1 = 0; i1 < account.getActive().getTuesday().getDetail().size(); i1++) {
                            String[] time = account.getActive().getTuesday().getDetail().get(i1).split("-");

                            //处于非激活时间内
                            if (TimeUtil.isInTime(time[0], time[1])) {
                                //不通过
                                return false;
                            }
                        }
                    }

                } else {
                    return false;
                }
                break;
            case 3:
                if (account.getActive().getWednesday().isEnable()) {

                    if (account.getActive().getWednesday().getDetail().isEmpty()) {
                        break;
                    } else {
                        //遍历非激活时间区间
                        for (int i1 = 0; i1 < account.getActive().getWednesday().getDetail().size(); i1++) {
                            String[] time = account.getActive().getWednesday().getDetail().get(i1).split("-");

                            //处于非激活时间内
                            if (TimeUtil.isInTime(time[0], time[1])) {
                                //不通过
                                return false;
                            }
                        }
                    }

                } else {
                    return false;
                }
                break;
            case 4:
                if (account.getActive().getThursday().isEnable()) {

                    if (account.getActive().getThursday().getDetail().isEmpty()) {
                        break;
                    } else {
                        //遍历非激活时间区间
                        for (int i1 = 0; i1 < account.getActive().getThursday().getDetail().size(); i1++) {
                            String[] time = account.getActive().getThursday().getDetail().get(i1).split("-");

                            //处于非激活时间内
                            if (TimeUtil.isInTime(time[0], time[1])) {
                                //不通过
                                return false;
                            }
                        }
                    }

                } else {
                    return false;
                }
                break;
            case 5:
                if (account.getActive().getFriday().isEnable()) {

                    if (account.getActive().getFriday().getDetail().isEmpty()) {
                        break;
                    } else {
                        //遍历非激活时间区间
                        for (int i1 = 0; i1 < account.getActive().getFriday().getDetail().size(); i1++) {
                            String[] time = account.getActive().getFriday().getDetail().get(i1).split("-");

                            //处于非激活时间内
                            if (TimeUtil.isInTime(time[0], time[1])) {
                                //不通过
                                return false;
                            }
                        }
                    }

                } else {
                    return false;
                }
                break;
            case 6:
                if (account.getActive().getSaturday().isEnable()) {

                    if (account.getActive().getSaturday().getDetail().isEmpty()) {
                        break;
                    } else {
                        //遍历非激活时间区间
                        for (int i1 = 0; i1 < account.getActive().getSaturday().getDetail().size(); i1++) {
                            String[] time = account.getActive().getSaturday().getDetail().get(i1).split("-");

                            //处于非激活时间内
                            if (TimeUtil.isInTime(time[0], time[1])) {
                                //不通过
                                return false;
                            }
                        }
                    }

                } else {
                    return false;
                }
                break;
            case 0:
                if (account.getActive().getSunday().isEnable()) {

                    if (account.getActive().getSunday().getDetail().isEmpty()) {
                        break;
                    } else {
                        //遍历非激活时间区间
                        for (int i1 = 0; i1 < account.getActive().getSunday().getDetail().size(); i1++) {
                            String[] time = account.getActive().getSunday().getDetail().get(i1).split("-");

                            //处于非激活时间内
                            if (TimeUtil.isInTime(time[0], time[1])) {
                                //不通过
                                return false;
                            }
                        }
                    }

                } else {
                    return false;
                }
                break;
        }
        return true;
    }

    @Override
    public boolean checkFreeze(AccountEntity account) {
        if (dynamicInfo.getFreezeUserInfoMap().containsKey(account.getId())) {
            //检测是否结束冻结
            if (dynamicInfo.getFreezeUserInfoMap().get(account.getId()).isBefore(GameDayClock.now())) {
                dynamicInfo.getFreezeUserInfoMap().remove(account.getId());
                dynamicInfo.getCooldownReasonMap().remove(account.getId());
                //解冻，不在冻结状态
                return false;
            }
            //仍处于冻结
            return true;

        } else {
            //不在冻结状态
            return false;
        }
    }

    public int restoreExpiredCooldownTasks() {
        var now = GameDayClock.now();
        var expiredIds = new java.util.ArrayList<Long>();
        synchronized (dynamicInfo.getFreezeUserInfoMap()) {
            for (java.util.Map.Entry<Long, LocalDateTime> entry : dynamicInfo.getFreezeUserInfoMap().entrySet()) {
                if (entry.getValue() == null || !entry.getValue().isAfter(now)) {
                    expiredIds.add(entry.getKey());
                }
            }
        }
        var restored = 0;
        for (Long id : expiredIds) {
            var account = accountMapper.selectById(id);
            synchronized (dynamicInfo.getFreezeUserInfoMap()) {
                var freezeUntil = dynamicInfo.getFreezeUserInfoMap().get(id);
                if (freezeUntil != null && freezeUntil.isAfter(now)) {
                    continue;
                }
                dynamicInfo.getFreezeUserInfoMap().remove(id);
                dynamicInfo.getCooldownReasonMap().remove(id);
            }
            if (isDeleted(account) || account.getFreeze() == 1 || account.getExpireTime().isBefore(now)) {
                continue;
            }
            if (dynamicInfo.getWorkUserList().contains(id)) {
                continue;
            }
            dispatchQueueService.restoreBest(id, now);
            restored++;
        }
        return restored;
    }

    public java.util.HashMap<Long, LocalDateTime> getActiveCooldownTaskMap() {
        restoreExpiredCooldownTasks();
        var result = new java.util.HashMap<Long, LocalDateTime>();
        var now = GameDayClock.now();
        synchronized (dynamicInfo.getFreezeUserInfoMap()) {
            dynamicInfo.getFreezeUserInfoMap().forEach((id, freezeUntil) -> {
                if (freezeUntil != null && freezeUntil.isAfter(now)) {
                    result.put(id, freezeUntil);
                }
            });
        }
        return result;
    }

    @Override
    public HashMap<Long, AccountCooldownVO> getActiveCooldownTaskInfoMap() {
        restoreExpiredCooldownTasks();
        return snapshotActiveCooldownTaskInfoMap(GameDayClock.now());
    }

    public HashMap<Long, AccountCooldownVO> snapshotActiveCooldownTaskInfoMap(LocalDateTime requestedNow) {
        var now = requestedNow == null ? GameDayClock.now() : requestedNow;
        var activeUntil = new LinkedHashMap<Long, LocalDateTime>();
        synchronized (dynamicInfo.getFreezeUserInfoMap()) {
            dynamicInfo.getFreezeUserInfoMap().forEach((id, freezeUntil) -> {
                if (id != null && freezeUntil != null && freezeUntil.isAfter(now)) {
                    activeUntil.put(id, freezeUntil);
                }
            });
        }

        var result = new HashMap<Long, AccountCooldownVO>();
        if (activeUntil.isEmpty()) {
            return result;
        }

        var reasons = new HashMap<Long, String>();
        synchronized (dynamicInfo.getCooldownReasonMap()) {
            activeUntil.keySet().forEach(id -> reasons.put(id,
                    dynamicInfo.getCooldownReasonMap().getOrDefault(id, "unknown")));
        }
        var accounts = accountMapper.selectBatchIds(activeUntil.keySet());
        if (accounts == null) {
            return result;
        }
        accounts.forEach(account -> {
            if (isDeleted(account)) {
                return;
            }
            var id = account.getId();
            var reason = reasons.getOrDefault(id, "unknown");
            result.put(id, new AccountCooldownVO(
                    account.getId(),
                    account.getName(),
                    account.getAccount(),
                    activeUntil.get(id),
                    reason,
                    cooldownReasonMessage(reason)
            ));
        });
        return result;
    }

    @Override
    public Result<String> showAccountCooldown(Long id) {
        if (id == null) {
            return Result.paramError("账号不能为空");
        }
        var account = accountMapper.selectById(id);
        if (isDeleted(account)) {
            return Result.notFound("账号不存在");
        }
        var freezeUntil = dynamicInfo.getFreezeUserInfoMap().get(id);
                if (freezeUntil != null && freezeUntil.isBefore(GameDayClock.now())) {
            dynamicInfo.getFreezeUserInfoMap().remove(id);
            freezeUntil = null;
        }
        return Result.success(freezeUntil == null ? null : freezeUntil.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME), "查询成功");
    }

    @Override
    public Result<String> setAccountCooldownUntil(Long id, String freezeUntil) {
        if (id == null) {
            return Result.paramError("账号不能为空");
        }
        var account = accountMapper.selectById(id);
        if (isDeleted(account)) {
            return Result.notFound("账号不存在");
        }
        if (freezeUntil == null || freezeUntil.isBlank()) {
            return Result.paramError("冷却时间不能为空");
        }
        LocalDateTime parsedFreezeUntil;
        try {
            parsedFreezeUntil = LocalDateTime.parse(freezeUntil.length() == 16 ? freezeUntil + ":00" : freezeUntil);
        } catch (Exception exception) {
            return Result.paramError("冷却时间格式错误");
        }
        if (!parsedFreezeUntil.isAfter(GameDayClock.now())) {
            return Result.paramError("冷却时间必须晚于当前时间");
        }
        forceHaltTask(id);
        putAccountOnCooldown(account, "SYSTEM", "manual", parsedFreezeUntil, false, true);
        return Result.success("设置成功");
    }

    @Override
    public Result<String> clearAccountCooldown(Long id) {
        if (id == null) {
            return Result.paramError("账号不能为空");
        }
        var account = accountMapper.selectById(id);
        if (isDeleted(account)) {
            return Result.notFound("账号不存在");
        }
        dynamicInfo.getFreezeUserInfoMap().remove(id);
        dynamicInfo.getCooldownReasonMap().remove(id);
        return Result.success("清除成功");
    }

    @Override
    public TaskAssignmentEntity lockTask(String deviceToken, AccountEntity account) {
        return taskAssignmentService.createAssignment(account, deviceToken, GameDayClock.now());
    }

    private TaskAssignmentEntity lockTask(String deviceToken, AccountEntity account,
                                          UrgentTaskEntity urgentTask, DispatchIntent intent) {
        if (urgentTask == null) {
            return taskAssignmentService.createAssignment(account, deviceToken, GameDayClock.now(),
                    TaskAssignmentService.MODE_NORMAL, null, intent);
        }
        var assignment = taskAssignmentService.createAssignment(account, deviceToken, GameDayClock.now(),
                UrgentTaskService.MODE_LOGIN_ONLY, urgentTask.getId(), intent);
        urgentTaskService.markRunning(urgentTask, GameDayClock.now());
        return assignment;
    }

    @Override
    public void log(String deviceToken, AccountEntity account, String level, String title,
                    String content, String imgUrl) {
        var addLogDTO = new AddLogDTO();
        TaskType type = TaskType.getByStr(account.getTaskType());

        String detail =
                "[" + type.getName() + "] [" + GameDayClock.now().format(DateTimeFormatter.ofPattern("HH:mm")) + "] " + type +
                        content;

        addLogDTO.setLevel(level)
                .setTaskType(account.getTaskType())
                .setTitle(title)
                .setDetail(detail)
                .setImageUrl(imgUrl)
                .setFrom(deviceToken)
                .setServer(account.getServer())
                .setName(account.getName())
                .setAccount(account.getAccount())
                .setAccountId(account.getId())
                .setAssignmentId(dynamicInfo.getWorkUserInfoMap().containsKey(account.getId())
                        ? dynamicInfo.getWorkUserInfoMap().get(account.getId()).getAssignmentId() : null);

        logService.addLog(addLogDTO, false);
    }

    @Override
    public void errorHandle(AccountEntity account, String deviceToken, String type) {
        errorHandle(account, deviceToken, type, true);
    }

    private void errorHandle(AccountEntity account, String deviceToken, String type, boolean requeue) {

        var errorType = type == null ? "unknown" : type;
        switch (errorType) {
            case ("lineBusy"): {
                putAccountOnCooldown(account, deviceToken, "lineBusy", GameDayClock.now().plusHours(1), requeue, true);
                break;
            }
            case ("accountError"): {
                if (account.getServer() == 0) {
                    if (httpService.isOfficialAccountWork(account.getAccount(), account.getPassword())) {
                        putAccountOnCooldown(account, deviceToken, "accountError", GameDayClock.now().plusHours(1), requeue, true);
                    } else {
                        account.setFreeze(1);
                        accountMapper.updateById(account);
                        dynamicInfo.getUserSanInfoMap().remove(account.getId());
                        logService.logWarn("[审判庭] 账号异常", "账号 " + account.getName() + "(" + account.getAccount() + ") 密码校验失败，已冻结账号。");
                        messageService.pushAdmin("[审判庭] 账号异常", "账号 " + account.getName() + "(" + account.getAccount() + ") 密码校验失败，已冻结账号。");
                        messageService.push(account, "账号异常", "您的账号密码有误，请在面板更新正确的账号密码，否则托管将无法继续进行");
                    }
                } else if (account.getServer() == 1) {
                    if (httpService.isBiliAccountWork(account.getAccount(), account.getPassword())) {
                        putAccountOnCooldown(account, deviceToken, "biliLoginLimit", GameDayClock.now().plusHours(1), requeue, true);
                        messageService.push(account, "账号异常", "您近期登陆的设备较多，已被B服限制登陆，请立即修改密码并于面板更新密码,否则托管可能将无法继续进行");
                    } else {
                        account.setFreeze(1);
                        accountMapper.updateById(account);
                        dynamicInfo.getUserSanInfoMap().remove(account.getId());
                        logService.logWarn("[审判庭] 账号异常", "账号 " + account.getName() + "(" + account.getAccount() + ") 密码校验失败，已冻结账号。");
                        messageService.pushAdmin("[审判庭] 账号异常", "账号 " + account.getName() + "(" + account.getAccount() + ") 密码校验失败，已冻结账号。");
                        messageService.push(account, "账号异常", "您的账号密码有误，请在面板更新正确的账号密码，否则托管将无法继续进行");
                    }
                }
                break;
            }
            default: {
                putAccountOnCooldown(account, deviceToken, errorType, GameDayClock.now().plusMinutes(10), requeue, true);
                break;
            }
        }
    }

    @Override
    public void forceHaltTask(Long id) {
        if (id == null) {
            return;
        }
        var assignment = taskAssignmentService.findByAccount(id).orElse(null);
        if (assignment != null) {
            synchronized (dynamicInfo.getHaltList()) {
                if (!dynamicInfo.getHaltList().contains(assignment.getDeviceToken())) {
                    dynamicInfo.getHaltList().add(assignment.getDeviceToken());
                }
            }
            taskAssignmentService.closeAssignment(assignment, "REVOKED", "administrator halted task", false);
        }
        dispatchQueueService.remove(id);
        synchronized (dynamicInfo.getWorkUserList()) {
            var workIterator = dynamicInfo.getWorkUserList().iterator();
            while (workIterator.hasNext()) {
                var worker = workIterator.next();
                if (worker.equals(id)) {
                    var waitHaltDevice = dynamicInfo.getWorkUserInfoMap().get(worker).getDeviceToken();
                    workIterator.remove();
                    dynamicInfo.getWorkUserInfoMap().remove(worker);
                    synchronized (dynamicInfo.getHaltList()) {
                        if (!dynamicInfo.getHaltList().contains(waitHaltDevice)) {
                            dynamicInfo.getHaltList().add(waitHaltDevice);
                        }
                    }
                    break;
                }
            }
        }
        dynamicInfo.getFreezeUserInfoMap().remove(id);
        dynamicInfo.getCooldownReasonMap().remove(id);
    }

    @Override
    public void calculatingSan() {
        //获取迭代器
        Iterator<Map.Entry<Long, UserSan>> entryIterator = dynamicInfo.getUserSanInfoMap().entrySet().iterator();

        //遍历所有用户
        while (entryIterator.hasNext()) {
            Long id = entryIterator.next().getKey();

            var account = accountMapper.selectById(id);

            //无效账号判空
            if (account == null) {
                entryIterator.remove();
                continue;
            }

            //检查是否已删除
            if (isDeleted(account)) {
                entryIterator.remove();
                continue;
            }

            //检查是否已冻结
            if (account.getFreeze() == 1) {
                entryIterator.remove();
                continue;
            }

            //检查是否已到期
            if (account.getExpireTime().isBefore(GameDayClock.now())) {
                entryIterator.remove();
                messageService.push(account, "到期提醒", "您的账号已到期，作战已暂停，若仍需托管请及时续费");
                continue;
            }

            //递增用户理智
            dynamicInfo.addUserSan(id, 1);

            var san = dynamicInfo.getUserSanInfoMap().get(id).getSan();
            var maxSan = dynamicInfo.getUserSanInfoMap().get(id).getMaxSan();

            //检查是否到达阈值 阈值为最大值-40
            if (san >= maxSan - 40) {
                if (dispatchQueueService.enqueueAuto(account.getId())) {
                    messageService.push(account, "等待分配作战服务器", "您的理智已达到 " + san +
                            "，等待分配作战服务器中，分配完成后将会自动开始作战");
                    dynamicInfo.setUserSanZero(id);
                }
            }

            //检查是否到达提醒阈值 阈值为最大值-45
            if (san == maxSan - 45) {
                messageService.push(account, "作战预告", "您的账号最快将在30" +
                        "分钟后开始作战，若您当前仍在线，请注意合理把握时间，避免被强制下线\n\n" +
                        "若您需要轮空本次作战，请前往面板-->设置-->冻结，手动冻结账号来进行轮空\n\n" +
                        "当前理智: " +
                        san +
                        "/" +
                        maxSan + "\n\n" +
                        "(可能存在误差，仅供参考)");
            }

        }

    }
}
