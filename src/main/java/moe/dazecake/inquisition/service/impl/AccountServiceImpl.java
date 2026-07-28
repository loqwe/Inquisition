package moe.dazecake.inquisition.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.google.gson.Gson;
import moe.dazecake.inquisition.constant.enums.TaskType;
import moe.dazecake.inquisition.mapper.AccountDispatchConfigMapper;
import moe.dazecake.inquisition.mapper.AccountDispatchTimeMapper;
import moe.dazecake.inquisition.mapper.AccountMapper;
import moe.dazecake.inquisition.mapper.AccountScheduledRunMapper;
import moe.dazecake.inquisition.mapper.mapstruct.AccountConvert;
import moe.dazecake.inquisition.model.dto.account.AccountDispatchConfigDTO;
import moe.dazecake.inquisition.model.dto.account.AccountDTO;
import moe.dazecake.inquisition.model.dto.account.AddAccountDTO;
import moe.dazecake.inquisition.model.entity.AccountDispatchConfigEntity;
import moe.dazecake.inquisition.model.entity.AccountDispatchTimeEntity;
import moe.dazecake.inquisition.model.entity.AccountEntity;
import moe.dazecake.inquisition.model.entity.AccountScheduledRunEntity;
import moe.dazecake.inquisition.model.entity.ConfigEntitySet.ConfigEntity;
import moe.dazecake.inquisition.model.entity.TaskAssignmentEntity;
import moe.dazecake.inquisition.model.local.DispatchIntent;
import moe.dazecake.inquisition.model.vo.account.AccountWithSanVO;
import moe.dazecake.inquisition.model.vo.query.PageQueryVO;
import moe.dazecake.inquisition.service.intf.AccountService;
import moe.dazecake.inquisition.utils.DailyPlanUtil;
import moe.dazecake.inquisition.utils.DynamicInfo;
import moe.dazecake.inquisition.utils.GameDayClock;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AccountServiceImpl implements AccountService {

    private final Gson gson = new Gson();

    @Resource
    DynamicInfo dynamicInfo;

    @Resource
    AccountMapper accountMapper;

    @Resource
    DailyLoginService dailyLoginService;

    @Resource
    MessageServiceImpl messageService;

    @Resource
    TaskServiceImpl taskService;

    @Resource
    DispatchQueueService dispatchQueueService;

    @Resource
    AccountDispatchConfigService dispatchConfigService;

    @Resource
    TaskAssignmentService taskAssignmentService;

    @Resource
    AccountScheduledRunService scheduledRunService;

    @Resource
    AccountDispatchConfigMapper dispatchConfigMapper;

    @Resource
    AccountDispatchTimeMapper dispatchTimeMapper;

    @Resource
    AccountScheduledRunMapper scheduledRunMapper;

    @Override
    public void addAccount(AddAccountDTO addAccountDTO) {
        var accountEntity = new AccountEntity();
        accountEntity.setName(addAccountDTO.getName())
                .setAccount(addAccountDTO.getAccount())
                .setPassword(addAccountDTO.getPassword())
                .setServer(addAccountDTO.getServer())
                .setExpireTime(addAccountDTO.getExpireTime());
        if (addAccountDTO.getFreeze() != null) {
            accountEntity.setFreeze(addAccountDTO.getFreeze());
        }
        if (addAccountDTO.getTaskType() != null) {
            accountEntity.setTaskType(addAccountDTO.getTaskType());
        }
        if (addAccountDTO.getRefresh() != null) {
            accountEntity.setRefresh(addAccountDTO.getRefresh());
        }
        if (addAccountDTO.getAgent() != null) {
            accountEntity.setAgent(addAccountDTO.getAgent());
        }
        if (addAccountDTO.getConfig() != null) {
            accountEntity.setConfig(addAccountDTO.getConfig());
        }
        if (addAccountDTO.getActive() != null) {
            accountEntity.setActive(addAccountDTO.getActive());
        }
        if (addAccountDTO.getNotice() != null) {
            accountEntity.setNotice(addAccountDTO.getNotice());
        }
        DailyPlanUtil.normalizeDailyPlan(accountEntity);

        accountMapper.insert(accountEntity);
    }

    @Override
    public int transferAccount(HashMap<String, String> accountJson) {
        var num = 0;

        for (int i = 1; i <= 30; i++) {
            if (accountJson.containsKey("username" + i) && accountJson.containsKey("password" + i)) {
                var account = new AccountEntity();

                //导入账号密码
                if (accountJson.get("username" + i).contains("#")) {
                    var parts = accountJson.get("username" + i).split("#");
                    account.setName(parts[1]);
                    account.setAccount(parts[0]);
                } else {
                    account.setName(accountJson.get("username" + i));
                    account.setAccount(accountJson.get("username" + i));
                }
                account.setPassword(accountJson.get("password" + i));
                if (accountJson.containsKey("server" + i)) {
                    account.setServer(Long.valueOf(accountJson.get("server" + i)));
                } else {
                    account.setServer(0L);
                }

                accountMapper.insert(account);
                num++;
            }
        }

        return num;
    }

    @Override
    @Transactional
    public void deleteAccount(Long id) {
        if (id != null) {
            taskService.forceHaltTask(id);
            var activeRun = scheduledRunService.findActiveByAccount(id).orElse(null);
            if (activeRun != null && !scheduledRunService.cancel(activeRun.getId())) {
                throw new IllegalStateException("Unable to cancel scheduled run before account deletion");
            }
            dispatchQueueService.remove(id);
            dynamicInfo.getUserSanInfoMap().remove(id);
            dynamicInfo.getFreezeUserInfoMap().remove(id);
            dynamicInfo.getCooldownReasonMap().remove(id);
            dispatchTimeMapper.deleteByAccountId(id);
            dispatchConfigMapper.deleteById(id);
            accountMapper.hardDeleteById(id);
        }
    }

    @Override
    @Transactional
    public void updateAccount(AccountDTO accountDTO, Set<String> presentFields) {
        updateAccount(accountDTO, presentFields, null);
    }

    @Override
    @Transactional
    public void updateAccount(AccountDTO accountDTO, Set<String> presentFields,
                              AccountDispatchConfigDTO dispatchConfig) {
        var account = accountMapper.selectById(accountDTO.getId());

        if (account != null) {
            var now = GameDayClock.now();
            var wasSchedulable = isSchedulableDailyAccount(account, now);
            mergeAccountUpdates(account, accountDTO, presentFields);
            if (presentFields != null && presentFields.contains("config")) {
                DailyPlanUtil.normalizeDailyPlan(account);
            }
            account.setUpdateTime(now);
            accountMapper.updateById(account);
            updateDispatchConfiguration(account, presentFields, dispatchConfig, now);
            reconcileAccountEligibility(account, presentFields, wasSchedulable, now);
        }
    }

    private void updateDispatchConfiguration(AccountEntity account, Set<String> presentFields,
                                             AccountDispatchConfigDTO requestedConfig,
                                             LocalDateTime now) {
        if (presentFields == null) {
            return;
        }
        var configWasProvided = presentFields.contains("dispatchConfig");
        var activeWeekChanged = presentFields.contains("active");
        if (!configWasProvided && !activeWeekChanged) {
            return;
        }
        var existingConfig = dispatchConfigService.getOrDefault(account.getId());
        if (!configWasProvided
                && !AccountDispatchConfigService.SCHEDULED.equals(existingConfig.getDispatchMode())) {
            return;
        }

        var effectiveRequest = requestedConfig;
        if (!configWasProvided) {
            effectiveRequest = new AccountDispatchConfigDTO();
            effectiveRequest.setDispatchMode(existingConfig.getDispatchMode());
            effectiveRequest.setScheduleTime(existingConfig.getScheduleTime());
            effectiveRequest.setScheduleTimes(
                    dispatchConfigService.getScheduleTimes(existingConfig));
        }
        if (effectiveRequest == null) {
            throw new IllegalArgumentException("dispatch configuration is required");
        }

        var assignment = taskAssignmentService.findByAccount(account.getId()).orElse(null);
        var activeRun = scheduledRunService.findActiveByAccount(account.getId()).orElse(null);
        var targetScheduled = AccountDispatchConfigService.SCHEDULED.equals(
                effectiveRequest.getDispatchMode());
        var deferActivation = shouldDeferActivation(targetScheduled, assignment, activeRun);
        dispatchConfigService.update(account, effectiveRequest, deferActivation, now);
        reconcileDispatchState(account.getId(), existingConfig, effectiveRequest,
                assignment, activeRun);
    }

    private void reconcileAccountEligibility(AccountEntity account, Set<String> presentFields,
                                             boolean wasSchedulable, LocalDateTime now) {
        var isSchedulable = isSchedulableDailyAccount(account, now);
        if (wasSchedulable == isSchedulable) {
            return;
        }
        if (!isSchedulable) {
            dispatchConfigMapper.clearNext(account.getId());
            var activeRun = scheduledRunService.findActiveByAccount(account.getId()).orElse(null);
            if (activeRun != null
                    && !AccountScheduledRunService.STATUS_RUNNING.equals(activeRun.getStatus())
                    && !scheduledRunService.cancel(activeRun.getId())) {
                throw new IllegalStateException("Unable to cancel scheduled run for inactive account");
            }
            dispatchQueueService.remove(account.getId());
            return;
        }

        var scheduleAlreadyRecalculated = presentFields != null
                && (presentFields.contains("dispatchConfig") || presentFields.contains("active"));
        if (scheduleAlreadyRecalculated) {
            return;
        }
        var config = dispatchConfigService.getOrDefault(account.getId());
        if (!AccountDispatchConfigService.SCHEDULED.equals(config.getDispatchMode())
                || taskAssignmentService.findByAccount(account.getId()).isPresent()
                || scheduledRunService.findActiveByAccount(account.getId()).isPresent()) {
            return;
        }
        var request = new AccountDispatchConfigDTO();
        request.setDispatchMode(config.getDispatchMode());
        request.setScheduleTime(config.getScheduleTime());
        request.setScheduleTimes(dispatchConfigService.getScheduleTimes(config));
        dispatchConfigService.update(account, request, false, now);
    }

    private boolean isSchedulableDailyAccount(AccountEntity account, LocalDateTime now) {
        return account != null
                && "daily".equals(account.getTaskType())
                && Integer.valueOf(0).equals(account.getDelete())
                && Integer.valueOf(0).equals(account.getFreeze())
                && account.getExpireTime() != null
                && account.getExpireTime().isAfter(now);
    }

    private boolean shouldDeferActivation(boolean targetScheduled,
                                          TaskAssignmentEntity assignment,
                                          AccountScheduledRunEntity activeRun) {
        if (targetScheduled) {
            return assignment != null || activeRun != null;
        }
        return (assignment != null
                && DispatchIntent.SOURCE_SCHEDULED.equals(assignment.getDispatchSource()))
                || (activeRun != null
                && AccountScheduledRunService.STATUS_RUNNING.equals(activeRun.getStatus()));
    }

    private void reconcileDispatchState(Long accountId, AccountDispatchConfigEntity existingConfig,
                                        AccountDispatchConfigDTO requestedConfig,
                                        TaskAssignmentEntity assignment,
                                        AccountScheduledRunEntity activeRun) {
        var targetScheduled = AccountDispatchConfigService.SCHEDULED.equals(
                requestedConfig.getDispatchMode());
        if (targetScheduled) {
            if (activeRun == null) {
                dispatchQueueService.remove(accountId);
            }
            return;
        }
        if (!AccountDispatchConfigService.SCHEDULED.equals(existingConfig.getDispatchMode())) {
            return;
        }
        if (activeRun != null
                && !AccountScheduledRunService.STATUS_RUNNING.equals(activeRun.getStatus())) {
            if (!scheduledRunService.cancel(activeRun.getId())) {
                throw new IllegalStateException("Unable to cancel scheduled run after mode change");
            }
            dispatchQueueService.remove(accountId);
            return;
        }
        if (activeRun == null && (assignment == null
                || !DispatchIntent.SOURCE_SCHEDULED.equals(assignment.getDispatchSource()))) {
            dispatchQueueService.remove(accountId);
        }
    }

    private void mergeAccountUpdates(AccountEntity account, AccountDTO accountDTO, Set<String> presentFields) {
        if (presentFields == null) {
            return;
        }
        if (presentFields.contains("name")) {
            account.setName(accountDTO.getName());
        }
        if (presentFields.contains("account")) {
            account.setAccount(accountDTO.getAccount());
        }
        if (presentFields.contains("password")) {
            account.setPassword(accountDTO.getPassword());
        }
        if (presentFields.contains("freeze")) {
            account.setFreeze(accountDTO.getFreeze());
        }
        if (presentFields.contains("server")) {
            account.setServer(accountDTO.getServer());
        }
        if (presentFields.contains("taskType")) {
            account.setTaskType(accountDTO.getTaskType());
        }
        if (presentFields.contains("config") && accountDTO.getConfig() != null) {
            account.setConfig(accountDTO.getConfig());
        }
        if (presentFields.contains("active") && accountDTO.getActive() != null) {
            account.setActive(accountDTO.getActive());
        }
        if (presentFields.contains("notice") && accountDTO.getNotice() != null) {
            account.setNotice(accountDTO.getNotice());
        }
        if (presentFields.contains("bLimitDevice") && accountDTO.getBLimitDevice() != null) {
            account.setBLimitDevice(accountDTO.getBLimitDevice());
        }
        if (presentFields.contains("refresh")) {
            account.setRefresh(accountDTO.getRefresh());
        }
        if (presentFields.contains("agent")) {
            account.setAgent(accountDTO.getAgent());
        }
        if (presentFields.contains("expireTime")) {
            account.setExpireTime(accountDTO.getExpireTime());
        }
        if (presentFields.contains("delete")) {
            account.setDelete(accountDTO.getDelete());
        }
    }

    @Override
    public PageQueryVO<AccountWithSanVO> queryAllAccount(Long current, Long size, String taskType, String freeze,
                                                         String expired, String deleted, String login) {
        return queryAllAccount(current, size, taskType, freeze, expired, deleted, login, GameDayClock.now());
    }

    public PageQueryVO<AccountWithSanVO> queryAllAccount(Long current, Long size, String taskType, String freeze,
                                                         String expired, String deleted, String login,
                                                         LocalDateTime requestedNow) {
        var now = requestedNow == null ? GameDayClock.now() : requestedNow;
        if ("missing".equalsIgnoreCase(login)) {
            var data = accountMapper.selectMissingDailyLoginPage(new Page<>(current, size), now,
                    GameDayClock.startOfGameDay(now));
            return getAccountWithSanVOPageQueryVO(data);
        }

        var wrapper = Wrappers.<AccountEntity>lambdaQuery();
        var deletedFilter = parseBooleanFilter(deleted);

        wrapper.eq(AccountEntity::getDelete, Boolean.TRUE.equals(deletedFilter) ? 1 : 0);

        if (taskType != null && !taskType.isBlank() && !"all".equalsIgnoreCase(taskType)) {
            wrapper.eq(AccountEntity::getTaskType, taskType);
        }

        var freezeFilter = parseBooleanFilter(freeze);
        if (freezeFilter != null) {
            wrapper.eq(AccountEntity::getFreeze, freezeFilter ? 1 : 0);
        }

        var expiredFilter = parseBooleanFilter(expired);
        if (expiredFilter != null) {
            wrapper.lt(expiredFilter, AccountEntity::getExpireTime, now)
                    .ge(!expiredFilter, AccountEntity::getExpireTime, now);
        }

        var data = accountMapper.selectPage(new Page<>(current, size), wrapper);
        return getAccountWithSanVOPageQueryVO(data);
    }

    @Override
    public PageQueryVO<AccountWithSanVO> queryAccount(Long current, Long size, String keyword) {
        var normalizedKeyword = keyword == null ? "" : keyword.trim();
        if (normalizedKeyword.isBlank()) {
            return queryAllAccount(current, size, null, null, null, null, null);
        }

        var data = accountMapper.searchActiveExactFirst(new Page<>(current, size), normalizedKeyword, parseIdKeyword(normalizedKeyword));
        return getAccountWithSanVOPageQueryVO(data);
    }

    private Long parseIdKeyword(String keyword) {
        try {
            return Long.valueOf(keyword);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Boolean parseBooleanFilter(String value) {
        if (value == null || value.isBlank() || "all".equalsIgnoreCase(value)) {
            return null;
        }
        if ("true".equalsIgnoreCase(value) || "1".equals(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value) || "0".equals(value)) {
            return false;
        }
        return null;
    }

    @Override
    public void resetAccountRefresh(Long id, Integer num) {
        var account = accountMapper.selectById(id);
        if (account != null) {
            account.setRefresh(num);
            accountMapper.updateById(account);
        }
    }

    @Override
    public String forceFightAccount(Long id, boolean isAdmin) {
        var account = accountMapper.selectById(id);
        if (account == null) {
            return "账号不存在";
        }
        //检查先决条件
        if (!isAdmin) {
            if (account.getDelete() == 1 || account.getExpireTime().isBefore(LocalDateTime.now())) {
                return "账号已到期或失效";
            }
            if (account.getFreeze() == 1) {
                return "请先解冻再执行操作";
            }
        } else {
            account.setFreeze(0);
        }
        //插队检查
        if (dispatchQueueService.contains(id)) {
            dispatchQueueService.enqueueManual(id);
            return "插队成功";
        }
        //上锁检查
        for (Long worker : dynamicInfo.getWorkUserList()) {
            if (worker.equals(id)) {
                return "已经在作战中";
            }
        }
        //资格检查
        if (!isAdmin) {
            if (account.getRefresh() < 1) {
                return "今日刷新次数已达上限，每天零点刷新，明天再来看看吧";
            }
        }
        //执行
        if (!dispatchQueueService.enqueueManual(account.getId())) {
            return "账号当前无法加入任务队列";
        }
        dynamicInfo.setUserSanZero(account.getId());
        account = accountMapper.selectById(id);
        account.setRefresh(account.getRefresh() - 1);
        accountMapper.updateById(account);
        return "立即开始作战成功，等待分配作战服务器";
    }

    @Override
    public String resetAccountDynamicInfo(Long id) {
        var account = accountMapper.selectById(id);
        if (account == null || account.getDelete() == 1 || account.getExpireTime().isBefore(LocalDateTime.now())) {

            return "不在激活状态，无需修复";

        }

        //停止作战
        taskService.forceHaltTask(id);

        //重置动态数据
        dynamicInfo.setUserSan(id, 135, 135);

        return "重置成功";
    }

    @Override
    public boolean initiateTaskConversion(TaskType taskType, Long userId, String params) {
        var user = accountMapper.selectById(userId);
        if (user == null || user.getDelete() == 1 || user.getFreeze() == 1) {
            return false;
        }

        user.setTaskType(taskType.getType());
        switch (taskType) {
            case ROGUE:
            case ROGUE2:
                user.getConfig().getRogue().setLevel(Integer.parseInt(params.split("\\|")[0]));
                user.getConfig().getRogue().setCoin(Integer.parseInt(params.split("\\|")[1]));
                addAccountExpireTime(userId, 24 * 3);
                break;
            case SAND_FIRE:
                addAccountExpireTime(userId, 24);
                break;
            default:
                return false;
        }
        accountMapper.updateById(user);

        forceFightAccount(userId, true);

        messageService.push(user, "作战类型切换", "您的作战类型已切换为: " + taskType.getName() + " 即将开始作战\n");

        return true;
    }

    @Override
    public void addAccountExpireTime(Long id, Integer hour) {
        var account = accountMapper.selectById(id);
        if (account != null) {
            account.setDelete(0);
            if (account.getExpireTime().isBefore(LocalDateTime.now())) {
                account.setExpireTime(LocalDateTime.now().plusHours(hour));
            } else {
                account.setExpireTime(account.getExpireTime().plusHours(hour));
            }
            account.setUpdateTime(LocalDateTime.now());
            account.setFreeze(0);
            accountMapper.updateById(account);
        }
    }

    @NotNull
    public PageQueryVO<AccountWithSanVO> getAccountWithSanVOPageQueryVO(Page<AccountEntity> data) {
        var result = new PageQueryVO<AccountWithSanVO>();
        result.setCurrent(data.getCurrent());
        result.setPage(data.getPages());
        result.setTotal(data.getTotal());
        Set<Long> accountIds = new HashSet<>();
        data.getRecords().forEach(account -> {
            if (account != null && account.getId() != null) {
                accountIds.add(account.getId());
            }
        });
        var todayLoginCounts = dailyLoginService.getLoginCounts(accountIds, GameDayClock.now());
        var dispatchConfigs = dispatchConfigs(accountIds);
        var scheduledAccountIds = dispatchConfigs.values().stream()
                .filter(config -> AccountDispatchConfigService.SCHEDULED.equals(
                        config.getDispatchMode()))
                .map(AccountDispatchConfigEntity::getAccountId)
                .collect(Collectors.toSet());
        var latestRuns = latestScheduledRuns(scheduledAccountIds);
        var scheduleTimes = dispatchTimes(scheduledAccountIds);

        for (AccountEntity user : data.getRecords()) {
            hydrateConfigFromRawJson(user);
            AccountWithSanVO accountWithSanVO;
            if (dynamicInfo.getUserSanInfoMap().containsKey(user.getId())) {
                accountWithSanVO = AccountConvert.INSTANCE.toAccountWithSanVO(
                        user,
                        dynamicInfo.getUserSanInfoMap().get(user.getId()).getSan() + "/" + dynamicInfo.getUserSanInfoMap().get(user.getId()).getMaxSan()
                );
            } else {
                accountWithSanVO = AccountConvert.INSTANCE.toAccountWithSanVO(user, "");
            }
            accountWithSanVO.setTodayLoginCount(todayLoginCounts.getOrDefault(user.getId(), 0));
            hydrateDispatch(accountWithSanVO, dispatchConfigs.get(user.getId()),
                    scheduleTimes.get(user.getId()),
                    latestRuns.get(user.getId()));
            result.getRecords().add(accountWithSanVO);
        }
        return result;
    }

    private Map<Long, AccountDispatchConfigEntity> dispatchConfigs(Set<Long> accountIds) {
        if (accountIds.isEmpty()) {
            return Map.of();
        }
        var rows = dispatchConfigMapper.selectBatchIds(accountIds);
        if (rows == null) {
            return Map.of();
        }
        return rows.stream().filter(Objects::nonNull)
                .collect(Collectors.toMap(AccountDispatchConfigEntity::getAccountId,
                        Function.identity(), (left, right) -> left));
    }

    private Map<Long, AccountScheduledRunEntity> latestScheduledRuns(Set<Long> accountIds) {
        if (accountIds.isEmpty()) {
            return Map.of();
        }
        var rows = scheduledRunMapper.selectLatestByAccountIds(accountIds);
        if (rows == null) {
            return Map.of();
        }
        return rows.stream().filter(Objects::nonNull)
                .collect(Collectors.toMap(AccountScheduledRunEntity::getAccountId,
                        Function.identity(), (left, right) -> left));
    }

    private Map<Long, List<LocalTime>> dispatchTimes(Set<Long> accountIds) {
        if (accountIds.isEmpty()) {
            return Map.of();
        }
        var rows = dispatchTimeMapper.selectByAccountIds(accountIds);
        if (rows == null || rows.isEmpty()) {
            return Map.of();
        }
        var result = new HashMap<Long, List<LocalTime>>();
        rows.stream().filter(Objects::nonNull)
                .filter(row -> row.getAccountId() != null && row.getScheduleTime() != null)
                .forEach(row -> result.computeIfAbsent(row.getAccountId(), ignored -> new ArrayList<>())
                        .add(row.getScheduleTime()));
        result.values().forEach(times -> times.sort(LocalTime::compareTo));
        return result;
    }

    private void hydrateDispatch(AccountWithSanVO target, AccountDispatchConfigEntity config,
                                 List<LocalTime> persistedTimes,
                                 AccountScheduledRunEntity latestRun) {
        if (config == null) {
            target.setDispatchMode(AccountDispatchConfigService.AUTO);
            return;
        }
        target.setDispatchMode(config.getDispatchMode());
        target.setNextScheduledAt(config.getNextScheduledAt());
        if (!AccountDispatchConfigService.SCHEDULED.equals(config.getDispatchMode())) {
            return;
        }
        var scheduleTimes = persistedTimes == null || persistedTimes.isEmpty()
                ? config.getScheduleTime() == null ? List.<LocalTime>of() : List.of(config.getScheduleTime())
                : new ArrayList<>(persistedTimes);
        target.setScheduleTimes(scheduleTimes);
        target.setScheduleTime(scheduleTimes.isEmpty() ? null : scheduleTimes.get(0));
        if (latestRun == null) {
            target.setScheduleStatus("NOT_RUN");
            return;
        }
        if (AccountScheduledRunService.STATUS_SUCCEEDED.equals(latestRun.getStatus())) {
            target.setScheduleStatus("NORMAL");
        } else if (AccountScheduledRunService.STATUS_CANCELLED.equals(latestRun.getStatus())) {
            target.setScheduleStatus("NOT_RUN");
        } else {
            target.setScheduleStatus(latestRun.getStatus());
        }
    }

    private void hydrateConfigFromRawJson(AccountEntity user) {
        if (user == null || user.getId() == null) {
            return;
        }
        var configJson = accountMapper.selectConfigJsonById(user.getId());
        if (configJson == null || configJson.isBlank()) {
            return;
        }
        var config = gson.fromJson(configJson, ConfigEntity.class);
        if (config != null) {
            user.setConfig(config);
            DailyPlanUtil.normalizeDailyPlan(user);
        }
    }


}
