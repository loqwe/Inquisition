# 管理员运营总览实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用一个无副作用的管理员聚合接口替换写死的旧总览，并让账号、任务、设备、脚本任务和经营数据与各管理页面保持同一口径。

**Architecture:** 后端以同一个 `GameDayClock.now()` 编排账号聚合 SQL、任务只读快照、设备运行时投影和脚本任务监控，返回带 `+08:00` 的单一快照。前端把类型、格式化和轮询状态机放入 `lib/admin-dashboard.ts`，页面只负责鉴权、刷新调度与区块组合；详情深链通过 URL 参数与既有管理页面双向同步。

**Tech Stack:** Java 11、Spring Boot 2.7、MyBatis-Plus、MySQL 8、JUnit 5、Mockito、Next.js 15、React 19、TypeScript、Tailwind CSS、shadcn/ui、Lucide、Node test runner、Playwright。

---

## 文件结构

后端工作树：`D:\自建功能\审判庭\_worktrees\inquisition-multi-schedule`

- 新建 `src/main/java/moe/dazecake/inquisition/controller/DashboardController.java`：管理员聚合接口。
- 新建 `src/main/java/moe/dazecake/inquisition/mapper/DashboardMetricsMapper.java`：账号与账单聚合 SQL。
- 新建 `src/main/java/moe/dazecake/inquisition/model/vo/dashboard/AdminDashboardOverviewVO.java`：聚合响应及所有脱敏子项。
- 新建 `src/main/java/moe/dazecake/inquisition/model/vo/dashboard/DashboardAccountMetrics.java`：账号聚合 SQL 投影。
- 新建 `src/main/java/moe/dazecake/inquisition/model/vo/dashboard/DashboardBusinessMetrics.java`：经营聚合 SQL 投影。
- 新建 `src/main/java/moe/dazecake/inquisition/model/vo/device/DeviceRuntimeProjection.java`：设备运行时统一投影。
- 新建 `src/main/java/moe/dazecake/inquisition/service/impl/AdminDashboardOverviewService.java`：只读快照编排、摘要和异常生成。
- 新建 `src/main/java/moe/dazecake/inquisition/service/impl/DeviceRuntimeProjectionService.java`：设备、运行时和活动分配的批量投影。
- 修改 `AccountController.java`、`AccountService.java`、`AccountServiceImpl.java`、`AccountMapper.java`：增加 `login=missing` 服务端分页。
- 修改 `TaskServiceImpl.java`、`TaskBoardService.java`：增加不恢复冷却、不重排队列的只读快照。
- 修改 `LoadDevice.java`、`DeviceServiceImpl.java`：设备管理复用统一运行时投影并保留旧字段。
- 新建及修改对应 JUnit 测试，覆盖口径、只读性、脱敏和协议兼容。

前端工作树：`D:\自建功能\审判庭\_worktrees\inquisition-panel-multi-schedule`

- 新建 `lib/admin-dashboard.ts` 和 `lib/admin-dashboard.test.mjs`：类型、状态、时间、URL 和刷新状态机。
- 新建 `app/api/getDashboardOverview/route.ts`：EdgeOne/Next 代理路由。
- 新建 `components/admin-dashboard/*.tsx`：指标、异常、任务、设备、账号、脚本和经营区块。
- 重写 `app/admin/dashboard/page.tsx`：15 秒无重叠轮询及总览组合。
- 修改 `components/dashboard-layout.tsx`：仅总览可传 1600px 内容宽度。
- 修改 `app/api/showAccount/route.ts`、`app/admin/users/page.tsx`：未登录服务端筛选及 URL 同步。
- 修改 `app/admin/tasks/page.tsx`、`app/admin/devices/page.tsx`、`app/admin/scheduled-tasks/page.tsx`：详情深链双向同步。
- 修改 `app/admin/settings/page.tsx`：迁入管理员密码表单。

### Task 1：未登录账号服务端分页

**Files:**
- Modify: `src/main/java/moe/dazecake/inquisition/mapper/AccountMapper.java`
- Modify: `src/main/java/moe/dazecake/inquisition/service/intf/AccountService.java`
- Modify: `src/main/java/moe/dazecake/inquisition/service/impl/AccountServiceImpl.java`
- Modify: `src/main/java/moe/dazecake/inquisition/controller/AccountController.java`
- Test: `src/test/java/moe/dazecake/inquisition/service/impl/AccountServiceImplTest.java`
- Test: `src/test/java/moe/dazecake/inquisition/controller/AccountControllerTest.java`

- [ ] **Step 1: 写失败测试，锁定 `login=missing` 的转发和分页分支**

```java
@Test
void missingLoginFilterUsesDatabasePageBeforeHydration() {
    var page = new Page<AccountEntity>(1, 10).setRecords(List.of(account(7L, "账号7"))).setTotal(1);
    when(service.accountMapper.selectMissingDailyLoginPage(any(), eq(now), eq(gameDayStart)))
            .thenReturn(page);

    var result = service.queryAllAccount(1L, 10L, null, null, null, null, "missing", now);

    assertEquals(1, result.getTotal());
    assertEquals(7L, result.getRecords().get(0).getId());
}
```

控制器测试捕获第七个筛选参数，断言 `/showAccount` 把 `missing` 原样传入服务层。

- [ ] **Step 2: 运行测试并确认失败**

Run: `./gradlew.bat test --tests "*AccountServiceImplTest" --tests "*AccountControllerTest"`

Expected: FAIL，原因是 Mapper 方法和新的服务签名尚不存在。

- [ ] **Step 3: 增加数据库分页查询**

在 `AccountMapper` 增加：

```java
@Select({
        "SELECT a.* FROM account a",
        "WHERE a.`delete` = 0 AND a.freeze = 0",
        "AND a.task_type = 'daily' AND a.expire_time >= #{now}",
        "AND NOT EXISTS (SELECT 1 FROM log l",
        "  WHERE l.account_id = a.id AND l.time >= #{gameDayStart}",
        "  AND l.`delete` = 0 AND UPPER(l.level) = 'INFO'",
        "  AND l.`from` IS NOT NULL AND l.`from` <> '' AND UPPER(l.`from`) <> 'SYSTEM'",
        "  AND l.title LIKE '%登录成功%')",
        "AND NOT EXISTS (SELECT 1 FROM task_assignment_history h",
        "  WHERE h.account_id = a.id AND h.finished_at >= #{gameDayStart}",
        "  AND h.status = 'COMPLETED' AND h.task_type = 'daily' AND h.task_mode = 'NORMAL')",
        "ORDER BY a.id ASC"
})
Page<AccountEntity> selectMissingDailyLoginPage(Page<AccountEntity> page,
                                                @Param("now") LocalDateTime now,
                                                @Param("gameDayStart") LocalDateTime gameDayStart);
```

- [ ] **Step 4: 扩展服务和控制器签名**

`AccountService.queryAllAccount` 增加 `String login`；`AccountServiceImpl` 在 `"missing".equalsIgnoreCase(login)` 时使用上面的 Mapper 查询，其余值沿用原 wrapper。公开重载接收 `LocalDateTime requestedNow` 供固定时间测试，原入口使用 `GameDayClock.now()`。

```java
public PageQueryVO<AccountWithSanVO> queryAllAccount(Long current, Long size, String taskType,
        String freeze, String expired, String deleted, String login) {
    return queryAllAccount(current, size, taskType, freeze, expired, deleted, login, GameDayClock.now());
}
```

`AccountController.showAccount` 增加 `String login`，`queryAccount` 的空关键词回退调用补一个 `null`。

- [ ] **Step 5: 跑定向测试并提交**

Run: `./gradlew.bat test --tests "*AccountServiceImplTest" --tests "*AccountControllerTest" --tests "*DailyLoginServiceTest"`

Expected: PASS。

```bash
git add src/main/java/moe/dazecake/inquisition/mapper/AccountMapper.java src/main/java/moe/dazecake/inquisition/service src/main/java/moe/dazecake/inquisition/controller/AccountController.java src/test/java/moe/dazecake/inquisition
git commit -m "feat: filter missing daily logins on the server"
```

### Task 2：任务看板纯只读快照

**Files:**
- Modify: `src/main/java/moe/dazecake/inquisition/service/impl/TaskServiceImpl.java`
- Modify: `src/main/java/moe/dazecake/inquisition/service/impl/TaskBoardService.java`
- Test: `src/test/java/moe/dazecake/inquisition/service/impl/TaskServiceImplTest.java`
- Test: `src/test/java/moe/dazecake/inquisition/service/impl/TaskBoardServiceTest.java`

- [ ] **Step 1: 写失败测试证明只读入口不触发恢复**

```java
@Test
void readOnlySnapshotDoesNotRestoreCooldownsOrMutateQueues() {
    var service = service();
    var now = LocalDateTime.of(2026, 7, 29, 10, 0);
    service.dynamicInfo.getFreezeUserInfoMap().put(7L, now.minusMinutes(1));
    service.dynamicInfo.setWaitUserList(new ArrayList<>(List.of(9L)));
    when(service.taskService.snapshotActiveCooldownTaskInfoMap(now)).thenReturn(new HashMap<>());

    service.getReadOnlySnapshot(now);

    verify(service.taskService, never()).restoreExpiredCooldownTasks();
    assertEquals(List.of(9L), service.dynamicInfo.getWaitUserList());
    assertTrue(service.dynamicInfo.getFreezeUserInfoMap().containsKey(7L));
}
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `./gradlew.bat test --tests "*TaskBoardServiceTest" --tests "*TaskServiceImplTest"`

Expected: FAIL，原因是纯只读方法尚不存在。

- [ ] **Step 3: 实现冷却映射快照**

在 `TaskServiceImpl` 增加 `snapshotActiveCooldownTaskInfoMap(LocalDateTime now)`：同步复制 `freezeUserInfoMap` 和 `cooldownReasonMap`，仅保留 `until.isAfter(now)` 的条目，一次 `selectBatchIds` 读取账号，构造新 `HashMap<Long, AccountCooldownVO>`。该方法不得调用 `restoreExpiredCooldownTasks()` 或 `dispatchQueueService`。

- [ ] **Step 4: 拆分看板构建入口**

```java
public TaskBoardVO getBoard(LocalDateTime requestedNow) {
    var now = requestedNow == null ? GameDayClock.now() : requestedNow;
    taskService.restoreExpiredCooldownTasks();
    return buildSnapshot(now);
}

public TaskBoardVO getReadOnlySnapshot(LocalDateTime requestedNow) {
    var now = requestedNow == null ? GameDayClock.now() : requestedNow;
    return buildSnapshot(now);
}
```

`buildSnapshot(now)` 只调用 `snapshotActiveCooldownTaskInfoMap(now)`，其余排序和任务投影保持原逻辑。

- [ ] **Step 5: 跑测试并提交**

Run: `./gradlew.bat test --tests "*TaskBoardServiceTest" --tests "*TaskServiceImplTest" --tests "*DispatchQueueServiceTest"`

Expected: PASS，且旧 `getBoard` 测试仍证明恢复入口存在。

```bash
git add src/main/java/moe/dazecake/inquisition/service/impl/TaskServiceImpl.java src/main/java/moe/dazecake/inquisition/service/impl/TaskBoardService.java src/test/java/moe/dazecake/inquisition/service/impl
git commit -m "refactor: add read-only task board snapshots"
```

### Task 3：统一设备运行时投影

**Files:**
- Create: `src/main/java/moe/dazecake/inquisition/model/vo/device/DeviceRuntimeProjection.java`
- Create: `src/main/java/moe/dazecake/inquisition/service/impl/DeviceRuntimeProjectionService.java`
- Modify: `src/main/java/moe/dazecake/inquisition/model/vo/device/LoadDevice.java`
- Modify: `src/main/java/moe/dazecake/inquisition/service/impl/DeviceServiceImpl.java`
- Test: `src/test/java/moe/dazecake/inquisition/service/impl/DeviceRuntimeProjectionServiceTest.java`
- Test: `src/test/java/moe/dazecake/inquisition/service/impl/DeviceServiceImplTest.java`

- [ ] **Step 1: 写四种互斥状态的失败测试**

固定 `now=2026-07-29T10:00`，构造心跳过期、有效暂停、有效且有活动分配、有效且无分配四台设备，断言顺序得到 `OFFLINE/SUSPENDED/BUSY/IDLE`，并断言 `online=3`、完整 Token 只存在内部投影。

- [ ] **Step 2: 运行测试并确认失败**

Run: `./gradlew.bat test --tests "*DeviceRuntimeProjectionServiceTest" --tests "*DeviceServiceImplTest"`

Expected: FAIL，原因是投影服务和字段尚不存在。

- [ ] **Step 3: 定义投影和状态优先级**

`DeviceRuntimeProjection` 包含 `DeviceEntity device`、`String runtimeState`、`LocalDateTime lastHeartbeatAt/offlineSince/suspendedUntil`、`Long currentAccountId`、`String currentAccountName`。服务公开：

```java
public List<DeviceRuntimeProjection> project(LocalDateTime now);
public List<DeviceRuntimeProjection> project(LocalDateTime now, List<RunningTaskVO> runningTasks);
```

两个入口均批量读取，不做逐设备查询。判定顺序固定为 `OFFLINE`、`SUSPENDED`、`BUSY`、`IDLE`，心跳有效边界与 `DeviceRuntimeService` 的 30 分钟规则一致。

- [ ] **Step 4: 扩展设备管理响应但保留旧字段**

在 `LoadDevice` 增加 `runtimeState`、三个时间字段和当前账号摘要。`DeviceServiceImpl.getLoadDevice()` 仍保留 `deviceToken` 和整数 `status`，但新字段来自 `DeviceRuntimeProjectionService`；不存在 runtime 记录时状态为 `OFFLINE`。

- [ ] **Step 5: 跑测试并提交**

Run: `./gradlew.bat test --tests "*DeviceRuntimeProjectionServiceTest" --tests "*DeviceRuntimeServiceTest" --tests "*DeviceServiceImplTest"`

Expected: PASS。

```bash
git add src/main/java/moe/dazecake/inquisition/model/vo/device src/main/java/moe/dazecake/inquisition/service/impl/DeviceRuntimeProjectionService.java src/main/java/moe/dazecake/inquisition/service/impl/DeviceServiceImpl.java src/test/java/moe/dazecake/inquisition/service/impl
git commit -m "feat: unify administrator device runtime states"
```

### Task 4：管理员聚合接口

**Files:**
- Create: `src/main/java/moe/dazecake/inquisition/mapper/DashboardMetricsMapper.java`
- Create: `src/main/java/moe/dazecake/inquisition/model/vo/dashboard/DashboardAccountMetrics.java`
- Create: `src/main/java/moe/dazecake/inquisition/model/vo/dashboard/DashboardBusinessMetrics.java`
- Create: `src/main/java/moe/dazecake/inquisition/model/vo/dashboard/AdminDashboardOverviewVO.java`
- Create: `src/main/java/moe/dazecake/inquisition/service/impl/AdminDashboardOverviewService.java`
- Create: `src/main/java/moe/dazecake/inquisition/controller/DashboardController.java`
- Test: `src/test/java/moe/dazecake/inquisition/service/impl/AdminDashboardOverviewServiceTest.java`
- Test: `src/test/java/moe/dazecake/inquisition/controller/DashboardControllerTest.java`

- [ ] **Step 1: 写失败测试锁定完整响应**

测试固定 `now`，模拟 63 个有效账号、2 个未登录、1 个长任务、3 台在线设备和 1 个失败脚本，断言：

```java
assertEquals("2026-07-29T14:00:00+08:00", overview.getGeneratedAt());
assertEquals("Asia/Shanghai", overview.getTimeZone());
assertEquals(61, overview.getAccounts().getLoggedToday());
assertEquals(2, overview.getAccounts().getMissingLogin());
assertEquals(1, overview.getTasks().getLongRunning());
assertEquals("WARNING", overview.getOverallStatus());
assertTrue(overview.getDevices().getItems().stream()
        .allMatch(item -> item.getTokenSuffix().length() <= 4));
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `./gradlew.bat test --tests "*AdminDashboardOverviewServiceTest" --tests "*DashboardControllerTest"`

Expected: FAIL，原因是聚合类型、服务和控制器尚不存在。

- [ ] **Step 3: 实现两条聚合 SQL**

`DashboardMetricsMapper.selectAccountMetrics(now, gameDayStart, sevenDaysLater)` 用 `SUM(CASE WHEN 条件 THEN 1 ELSE 0 END)` 一次返回有效日常、未登录、冻结、七天到期、今日新增和有效账号；未登录条件复用 Task 1 的两个 `NOT EXISTS`。`selectBusinessMetrics(dayStart, dayEnd, monthStart, monthEnd)` 分别使用 `COALESCE(SUM(CASE WHEN state = 1 AND update_time >= #{dayStart} AND update_time < #{dayEnd} THEN actual_pay_amount ELSE 0 END), 0)` 和对应月边界表达式返回日收入与月收入。

- [ ] **Step 4: 定义脱敏响应 VO**

`AdminDashboardOverviewVO` 用嵌套静态类承载 `Accounts/Tasks/Devices/ScheduledTasks/Business/Alert`。所有时间在服务中通过：

```java
private String offset(LocalDateTime value) {
    return value == null ? null : value.atZone(GameDayClock.ZONE_ID)
            .toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
}
```

设备项只含 ID、名称、Token 后 4 位、运行状态、时间和当前账号摘要；任务项不含账号密码、完整配置或完整 Token。

- [ ] **Step 5: 编排单一只读快照**

`AdminDashboardOverviewService.getOverview(LocalDateTime requestedNow)` 只调用 `TaskBoardService.getReadOnlySnapshot(now)`、设备投影、`ScheduledTaskMonitorService.getOverview(now)`、聚合 Mapper 和缺失账号前 5 条查询。异常排序为 `CRITICAL` 在前、`WARNING` 次之；26 点未登录、全部设备离线和加急长任务为 `CRITICAL`。

- [ ] **Step 6: 暴露管理员接口**

```java
@RestController
public class DashboardController {
    @Resource AdminDashboardOverviewService dashboardOverviewService;

    @Login
    @GetMapping("/getDashboardOverview")
    public Result<AdminDashboardOverviewVO> getDashboardOverview() {
        return Result.success(dashboardOverviewService.getOverview(GameDayClock.now()), "查询成功");
    }
}
```

- [ ] **Step 7: 跑测试并提交**

Run: `./gradlew.bat test --tests "*AdminDashboardOverviewServiceTest" --tests "*DashboardControllerTest" --tests "*ScheduledTaskMonitorServiceTest" --tests "*TaskBoardServiceTest"`

Expected: PASS。

```bash
git add src/main/java/moe/dazecake/inquisition/controller/DashboardController.java src/main/java/moe/dazecake/inquisition/mapper/DashboardMetricsMapper.java src/main/java/moe/dazecake/inquisition/model/vo/dashboard src/main/java/moe/dazecake/inquisition/service/impl/AdminDashboardOverviewService.java src/test/java/moe/dazecake/inquisition
git commit -m "feat: add administrator dashboard overview API"
```

### Task 5：后端兼容与完整回归

**Files:**
- Modify: `src/test/java/moe/dazecake/inquisition/controller/LegacyClientHttpContractTest.java`
- Modify: `src/test/java/moe/dazecake/inquisition/controller/TaskControllerTest.java`
- Test: all backend tests

- [ ] **Step 1: 扩展设备协议 smoke regression**

在本地夹具中分别调用 `/heartBeat`、`/getTask`、`/completeTask`、`/failTask`，断言原参数名和响应 `code/msg/data` 不变；总览接口调用前后比较等待队列、分配和冷却映射。

- [ ] **Step 2: 验证响应脱敏与旧接口**

序列化 `AdminDashboardOverviewVO`，断言 JSON 不含 `password`、`deviceToken`、`pushPlusToken`、`skland` 和完整配置；同时运行 `/getStatistics` 与 `/showLoadedDevice` 旧字段断言。

- [ ] **Step 3: 运行完整后端回归和构建**

Run: `./gradlew.bat clean test bootJar`

Expected: 全部测试 PASS，`build/libs/inquisition-1.3.1.jar` 生成。

- [ ] **Step 4: 提交测试收口**

```bash
git add src/test
git commit -m "test: cover dashboard compatibility contracts"
```

### Task 6：前端类型、代理和宽屏容器

**Files:**
- Create: `lib/admin-dashboard.ts`
- Create: `lib/admin-dashboard.test.mjs`
- Create: `app/api/getDashboardOverview/route.ts`
- Modify: `components/dashboard-layout.tsx`
- Modify: `package.json`

- [ ] **Step 1: 写失败的纯函数测试**

```javascript
test("formats dashboard timestamps in Asia/Shanghai", () => {
  assert.equal(formatDashboardTime("2026-07-29T14:00:00+08:00"), "07/29 14:00")
})

test("polling state never starts a second in-flight request", () => {
  assert.equal(shouldStartDashboardRefresh({ visible: true, inFlight: true }), false)
})
```

同时覆盖百分比夹紧、Token 后缀、合法深链枚举和过期状态。

- [ ] **Step 2: 运行测试并确认失败**

Run: `pnpm exec node --no-warnings --experimental-strip-types --test lib/admin-dashboard.test.mjs`

Expected: FAIL，原因是模块尚不存在。

- [ ] **Step 3: 实现类型和纯函数**

在 `lib/admin-dashboard.ts` 定义与后端逐字段一致的 `AdminDashboardOverview`，导出 `formatDashboardTime`、`formatGameDay`、`clampRate`、`statusMeta`、`parse*Filter` 和 `shouldStartDashboardRefresh`。所有 `Intl.DateTimeFormat` 固定 `timeZone: "Asia/Shanghai"`，字母间距不做负值处理。

- [ ] **Step 4: 增加代理与可选宽度**

`app/api/getDashboardOverview/route.ts` 按现有 `/getStatistics` 代理模式转发管理员 Bearer Token。`DashboardLayout` 增加：

```tsx
interface DashboardLayoutProps {
  children: React.ReactNode
  contentClassName?: string
}
```

默认仍为 `max-w-7xl`；总览传 `max-w-[1600px]`。

- [ ] **Step 5: 注册测试脚本并提交**

在 `package.json` 增加 `"test:dashboard": "node --no-warnings --experimental-strip-types --test lib/admin-dashboard.test.mjs"`。

Run: `pnpm test:dashboard`

Expected: PASS。

```bash
git add lib/admin-dashboard.ts lib/admin-dashboard.test.mjs app/api/getDashboardOverview/route.ts components/dashboard-layout.tsx package.json
git commit -m "feat: add dashboard client contracts"
```

### Task 7：四个详情深链与设备页统一状态

**Files:**
- Modify: `app/api/showAccount/route.ts`
- Modify: `app/admin/users/page.tsx`
- Modify: `app/admin/tasks/page.tsx`
- Modify: `app/admin/devices/page.tsx`
- Modify: `app/admin/scheduled-tasks/page.tsx`
- Test: `lib/admin-dashboard.test.mjs`

- [ ] **Step 1: 增加 URL 参数解析失败测试**

断言只接受 `login=missing`、`tab=pending|inProgress|exception`、`state=all|idle|busy|suspended|offline`、脚本任务已知筛选；未知值回退默认。

- [ ] **Step 2: 透传未登录筛选并改为服务端分页**

`app/api/showAccount/route.ts` 只在 `login === "missing"` 时追加查询参数。用户页通过 `useSearchParams` 初始化筛选，请求中带 `login`，不对当前页 `todayLoginCount` 做过滤；重置筛选时删除 URL 参数并回到第一页。

- [ ] **Step 3: 同步任务和脚本筛选 URL**

任务页和脚本页使用 `useSearchParams` + `useRouter`：参数变化更新 state，用户点击时 `router.replace` 保留其他参数。浏览器前进后退必须恢复对应页签。

- [ ] **Step 4: 设备页使用 `runtimeState`**

扩展设备类型的运行时字段，状态徽标和 `state=offline` 筛选只读取 `runtimeState`。旧 `status` 继续留在类型中供既有操作使用，完整 Token 的搜索、编辑和删除行为保持不变。

- [ ] **Step 5: 构建验证并提交**

Run: `pnpm test:dashboard && pnpm build`

Expected: PASS，四个页面均可由静态构建编译。

```bash
git add app/api/showAccount/route.ts app/admin/users/page.tsx app/admin/tasks/page.tsx app/admin/devices/page.tsx app/admin/scheduled-tasks/page.tsx lib/admin-dashboard.test.mjs
git commit -m "feat: connect dashboard detail filters"
```

### Task 8：迁移管理员密码表单

**Files:**
- Modify: `app/admin/settings/page.tsx`
- Modify: `app/admin/dashboard/page.tsx`

- [ ] **Step 1: 抽取原表单行为到其他设置**

在设置页增加独立“账号安全”区块，状态固定为：

```tsx
const [passwordForm, setPasswordForm] = useState({
  username: "",
  oldPassword: "",
  newPassword: "",
})
const [isPasswordSubmitting, setIsPasswordSubmitting] = useState(false)
```

提交仍调用 `/changeAdminPassword`，保存时禁用按钮，成功后清空三个字段，错误继续使用 toast。

- [ ] **Step 2: 从总览删除密码和写死系统状态**

删除旧 `/getStatistics` 请求、修改密码状态与表单、固定“系统负载/数据库/API 响应时间/在线用户”内容，确保总览文件不再引用 `Lock`、`Input`、`Label`。

- [ ] **Step 3: 构建验证并提交**

Run: `pnpm build`

Expected: PASS，设置页仍能静态编译。

```bash
git add app/admin/settings/page.tsx app/admin/dashboard/page.tsx
git commit -m "refactor: move administrator password settings"
```

### Task 9：管理员总览 UI 与轮询

**Files:**
- Create: `components/admin-dashboard/overview-metrics.tsx`
- Create: `components/admin-dashboard/alert-strip.tsx`
- Create: `components/admin-dashboard/task-snapshot.tsx`
- Create: `components/admin-dashboard/device-snapshot.tsx`
- Create: `components/admin-dashboard/account-progress.tsx`
- Create: `components/admin-dashboard/scheduled-task-health.tsx`
- Create: `components/admin-dashboard/business-summary.tsx`
- Modify: `app/admin/dashboard/page.tsx`
- Test: `lib/admin-dashboard.test.mjs`

- [ ] **Step 1: 实现固定尺寸六指标和异常条**

指标在宽屏 `xl:grid-cols-6`、中屏 `md:grid-cols-3`、手机 `grid-cols-2`，每项固定最小高度；图标使用 Lucide。异常条仅在有异常时展开，无异常显示一行紧凑状态，不显示空卡片。

- [ ] **Step 2: 实现任务与设备区块**

桌面使用 `lg:grid-cols-[minmax(0,2fr)_minmax(320px,1fr)]`，移动端单列。任务最多 5 条，设备按离线、暂停、忙碌、空闲排序最多 5 条；长文本使用 `min-w-0 break-words`，时间换行但不横向溢出。

- [ ] **Step 3: 实现账号、脚本和经营区块**

账号进度包含进度条和四个深链之一；脚本异常直接使用后端状态与脱敏摘要；经营数据为底部紧凑横向条，不添加趋势图或装饰背景。

- [ ] **Step 4: 实现 15 秒无重叠轮询**

页面维护 `snapshot/loading/refreshing/stale/lastSuccess/error`，使用 `AbortController` 和 ref 防重入。`document.hidden` 时停止 interval，恢复可见时立即刷新；刷新失败保留旧快照，成功后清除 stale。卸载时 abort。

- [ ] **Step 5: 完成空态、错误和认证状态**

初次加载使用稳定骨架；初次失败显示重试按钮；已有数据失败只显示“数据已过期”。无 token 沿用返回登录页行为，接口响应非 200 显示服务端 `msg`。

- [ ] **Step 6: 前端全量验证并提交**

Run: `pnpm test:accounts && pnpm test:scheduled && pnpm test:tasks && pnpm test:dashboard && pnpm build`

Expected: 所有 Node 测试 PASS，Next 生产构建 PASS。

```bash
git add components/admin-dashboard app/admin/dashboard/page.tsx lib/admin-dashboard.ts lib/admin-dashboard.test.mjs
git commit -m "feat: build administrator operations dashboard"
```

### Task 10：集成、视觉和发布前验收

**Files:**
- Verify only; only fix files implicated by failures

- [ ] **Step 1: 后端完整回归**

Run: `./gradlew.bat clean test bootJar`

Expected: 全部测试 PASS，bootJar 成功；外部副作用测试保持现有禁用策略。

- [ ] **Step 2: 前端完整回归**

Run: `pnpm test:accounts && pnpm test:scheduled && pnpm test:tasks && pnpm test:dashboard && pnpm build`

Expected: 全部测试和构建 PASS。

- [ ] **Step 3: 本地联调四个深链和轮询**

启动后端测试配置及前端开发服务，使用管理员 JWT 打开总览。验证 `/admin/users?login=missing`、`/admin/tasks?tab=inProgress`、`/admin/devices?state=offline`、`/admin/scheduled-tasks?filter=ABNORMAL`，并验证刷新、前进、后退、页面隐藏和恢复。

- [ ] **Step 4: Playwright 截图与布局检查**

在 `1920x1080`、`1366x768`、`768x1024`、`390x844` 截图，检查无重叠、无横向滚动、数字区域稳定、按钮有可访问名称、最长账号和设备名称不溢出。确认总览宽屏达到 1600px，而其他页面仍为 `max-w-7xl`。

- [ ] **Step 5: 真实 MySQL 只读验收**

对照同一分钟的账号未登录数、任务看板、设备运行时、脚本任务和经营聚合。连续轮询至少 2 分钟，确认队列、冷却和分配未因总览读取改变，接口 p95 小于 500ms，MySQL 无新增慢查询。

- [ ] **Step 6: 生产配置和权限验收**

确认 `INQUISITION_DEV_MODE=false`；管理员 JWT 返回 200，普通用户 JWT 和无 JWT 被拒绝。用本地设备夹具再跑 `/heartBeat`、`/getTask`、`/completeTask`、`/failTask`。

- [ ] **Step 7: 检查工作树和提交最终修复**

Run: `git status --short && git diff --check`

Expected: 只有本功能预期文件；无空白错误。

```bash
git add -u
git commit -m "fix: finish dashboard integration checks"
```

若验收没有产生修复，不创建空提交。此阶段不推送远端、不部署服务器，等待明确的推送与部署指令。
