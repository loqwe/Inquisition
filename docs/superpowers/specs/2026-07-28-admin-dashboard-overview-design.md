# 管理员运营总览设计

## 目标

把现有管理员总览从“财务卡片 + 修改密码 + 写死系统状态”改造成审判庭的实时运营监控台。管理员进入页面后，应在一个首屏内回答以下问题：

1. 当前游戏日还有多少账号没有登录？
2. 是否有加急、积压、长时间运行或冷却任务？
3. 哪些设备在线、空闲、忙碌、暂停或离线？
4. 后台脚本任务是否存在失败、错过或卡住？
5. 是否存在需要立即处理的异常？

总览只展示摘要、异常和前几条关键对象，完整编辑与批量操作继续留在用户管理、任务管理、设备管理和脚本任务页面。

## 已确认决策

- 采用单一后端聚合接口 `GET /getDashboardOverview`。
- 聚合接口使用同一个 `Asia/Shanghai` 当前时间和 04:00 游戏日边界计算所有区块。
- 首屏优先级为：异常 > 账号登录进度 > 任务 > 设备 > 脚本任务 > 经营数据。
- 页面每 15 秒后台刷新，保留手动刷新；刷新请求不得重叠。
- 现有 `/getStatistics` 第一阶段继续保留，避免旧前端或其他调用方失效。
- “今日未登录”必须由后端按完整数据集筛选和分页，不能在用户页拿到单页数据后再做前端过滤。
- 总览和设备管理必须共享同一套设备运行时投影；`DynamicInfo.deviceStatusMap` 只保留旧协议兼容用途，不再作为管理界面的状态事实来源。
- 修改管理员密码从总览移到“其他设置”的“账号安全”区块。
- 删除写死的“系统负载、数据库状态、API 响应时间、在线用户”展示。
- 第一阶段不增加历史趋势表、折线图、CPU/内存监控或新的数据库表。

## 非目标

- 不把总览变成用户、任务、设备的第二套完整管理页面。
- 不在总览直接执行结束任务、删除设备、冻结账号等高风险操作。
- 不实现历史 7 天或 30 天趋势；该能力需要后续快照表或指标系统。
- 不修改设备 Lua 协议和任务下发 JSON。
- 不改变 14 点补登、26 点加急、定时账号或设备离线的业务规则。
- 不删除旧统计接口和旧账单数据。

## 当前问题

现有 `app/admin/dashboard/page.tsx` 只请求 `/getStatistics`，展示新用户、月收入、日收入和所谓“付费用户”。下半部分的系统状态是固定文案，不来自真实监控；修改密码表单占据半个首屏。当前真正可用的任务看板、设备心跳、今日登录次数和脚本任务状态分别散落在其他页面。

当前 `DashboardLayout` 将所有页面限制为 `max-w-7xl`。在 2K 宽屏上，总览只有约 1280px 内容宽度，无法利用右侧空间展示运营信息。

## 信息架构

### P0：首屏必须出现

#### 页面标题栏

左侧显示“管理员总览”；右侧显示：

- 当前游戏日，例如 `游戏日 07/28 · 04:00 起`；
- 最近一次成功刷新时间；
- 数据状态：实时、刷新中或数据已过期；
- 图标式手动刷新按钮。

标题栏不显示说明教程或操作提示。

#### 六个紧凑指标

1. **有效日常**：未删除、未冻结、未过期且 `taskType=daily` 的账号数。
2. **今日已登录**：`loggedToday / eligibleDaily`，副信息显示未登录数量和覆盖率。
3. **待处理**：普通、手动、定时等待任务总数；副信息显示 26 点加急数量。
4. **进行中**：当前租约数量；副信息显示运行超过 2 小时的数量。
5. **在线设备**：`online / total`；副信息显示空闲、忙碌和离线数量。
6. **异常项**：当前可操作异常数量；无异常时显示“0 / 正常”。

指标块使用固定高度和稳定数字区域，加载状态不得改变网格尺寸。桌面宽屏为 6 列，中等桌面为 3 列两行，手机为 2 列三行。

#### 异常条

异常条只在存在异常时展开，按严重程度排序：

1. 设备离线或暂停；
2. 运行超过 2 小时的任务；
3. 脚本任务 `FAILED`、`MISSED` 或 `STALLED`；
4. 到达补登阶段后仍未登录的账号。

无异常时只保留一行紧凑的绿色状态，不显示大面积空容器。

未登录账号的严重度随游戏日时间变化：

- 04:00 至 13:59：只作为账号进度，不计入异常；
- 14:00 至次日 01:59：`WARNING`；
- 次日 02:00 至 03:59：`CRITICAL`。

每条异常包含类型、对象名称、发生或持续时间和详情页链接。总览不提供危险操作按钮。

### P1：支持判断的实时区块

#### 任务运行情况

占桌面主区域约三分之二宽度，显示：

- 加急、定时、普通待处理和进行中的数量；
- 当前运行任务前 5 条；
- 高优先等待任务前 5 条；
- 每条任务的账号名称、来源、设备名称、最近进度、开始时间和已运行时长；
- “查看全部”链接进入 `/admin/tasks`。

排序沿用任务管理：26 点加急 > 定时任务 = 立即上号 > 普通自动任务；同级按进入队列时间排序。总览不得重新定义一套优先级。

#### 设备状态

占桌面主区域约三分之一宽度，显示未删除设备的完整状态数量，以及优先级最高的前 5 台设备：

- 设备名称；
- 空闲、忙碌、暂停或离线主状态；
- 当前账号名称，空闲时显示“暂无任务”；
- 最后心跳时间；
- 离线持续时间或暂停截止时间；
- “查看全部”链接进入 `/admin/devices`。

总览响应只返回设备 ID、名称和 Token 后 4 位，不返回完整设备 Token。

设备条目排序为：离线 > 暂停 > 忙碌 > 空闲；同状态按设备名称稳定排序。完整设备列表继续由设备管理页承载。

#### 今日账号进度

显示：

- 登录覆盖率进度条；
- 已登录、未登录、冻结、冷却、7 天内到期数量；
- 未登录账号前 5 条；
- 每条未登录账号显示名称、账号 ID、调度方式和当前任务状态；
- “查看全部”链接进入 `/admin/users?login=missing`。

今日登录口径继续复用 `DailyLoginService`：显式“登录成功”日志与成功完成的完整日常任务按 `assignmentId` 去重。总览不得自行复制或简化该规则。

#### 脚本任务健康

显示全部、正常、运行中、异常、等待和停用数量，以及最多 5 条异常任务：

- 任务名称和状态；
- 最近成功或失败时间；
- 连续失败次数；
- 已脱敏的最近错误；
- 下一次运行时间；
- “查看全部”链接进入 `/admin/scheduled-tasks?filter=ABNORMAL`。

状态映射完全复用 `ScheduledTaskMonitorService`，前端不得根据时间重新推导第二套状态。

### P2：低频经营信息

经营数据放在页面底部的紧凑横向区域：

- 今日新增账号；
- 有效账号；
- 今日收入；
- 本月收入。

这些信息不参与系统异常状态，也不占用首屏最高优先区域。

### 修改密码迁移

从总览删除修改密码表单，在 `app/admin/settings/page.tsx` 增加“账号安全”区块，继续复用 `/changeAdminPassword` 和现有字段：用户名、当前密码、新密码。保存状态、错误提示和成功后清空行为保持不变。

## 页面骨架

```text
管理员总览                      游戏日 / 更新时间 / 刷新

有效日常 | 今日已登录 | 待处理 | 进行中 | 在线设备 | 异常项

异常与提醒（有异常时展开；正常时一行）

任务运行情况（2/3）             设备状态（1/3）

今日账号进度（1/2）             脚本任务健康（1/2）

经营数据
```

页面区块使用全宽分区、细边框和标题行，不把大区块做成层层嵌套的浮动卡片。只有六个重复指标和单条重复对象可使用小型卡片，圆角不超过 8px。

## 响应式规则

### 宽屏（1440px 以上）

- 总览内容宽度上限为 1600px；
- 六个指标一行；
- 任务与设备使用 2:1 布局；
- 账号进度与脚本任务使用 1:1 布局。

### 普通桌面和平板横屏（1024px 至 1439px）

- 指标为 3 列两行；
- 任务与设备保持两列，但设备列最小 320px；
- 账号与脚本区块可继续两列。

### 平板和手机（1024px 以下）

- 所有业务区块改为单列；
- 指标为 2 列；
- 任务和账号列表只显示最高优先 3 条；
- 次要时间信息换行，不允许横向溢出；
- 不依赖 hover，所有状态和链接均可触控与键盘访问。

`DashboardLayout` 增加可选的内容宽度参数，默认仍为 `max-w-7xl`；只有管理员总览传入 1600px 上限，避免改变其他页面布局。

## 聚合接口

### 路由与权限

- 方法：`GET`
- 路径：`/getDashboardOverview`
- 权限：必须使用现有 `@Login`，仅接受 `type=admin` 的有效 JWT。
- 请求体：无。
- 查询参数：第一阶段无。

### 响应结构

```json
{
  "generatedAt": "2026-07-28T23:40:00+08:00",
  "timeZone": "Asia/Shanghai",
  "gameDay": "2026-07-28",
  "gameDayStartedAt": "2026-07-28T04:00:00+08:00",
  "overallStatus": "HEALTHY",
  "alertCount": 0,
  "accounts": {
    "eligibleDaily": 63,
    "loggedToday": 63,
    "missingLogin": 0,
    "loginRate": 100.0,
    "frozen": 1,
    "coolingDown": 0,
    "expiringWithinSevenDays": 0,
    "missingItems": []
  },
  "tasks": {
    "urgent": 0,
    "pending": 1,
    "inProgress": 1,
    "scheduledWaiting": 0,
    "scheduledRunning": 1,
    "longRunning": 0,
    "runningItems": [],
    "priorityWaitingItems": []
  },
  "devices": {
    "total": 3,
    "online": 3,
    "idle": 2,
    "busy": 1,
    "offline": 0,
    "suspended": 0,
    "items": []
  },
  "scheduledTasks": {
    "total": 16,
    "healthy": 16,
    "running": 0,
    "abnormal": 0,
    "waiting": 0,
    "disabled": 0,
    "abnormalItems": []
  },
  "business": {
    "newAccountsToday": 0,
    "validAccounts": 64,
    "dayIncome": 0.0,
    "monthIncome": 0.0
  },
  "alerts": []
}
```

所有列表字段最多返回 5 条，异常列表最多返回 20 条。数量始终表示完整集合数量，不受列表截断影响。

### 列表项最小字段

任务项只返回：分配 ID、账号 ID、账号名称、任务模式、调度来源、设备名称、开始时间、运行分钟、最近进度标题和租约到期时间。不得返回账号密码或完整任务配置。

设备项只返回：设备 ID、名称、Token 后 4 位、运行状态、最后心跳、离线开始、暂停截止和当前账号摘要。不得返回完整 Token、远程控制参数或第三方凭据。

未登录账号项只返回：账号 ID、名称、调度方式、下一定时时间和当前任务状态。不得返回游戏账号密码、通知凭据或森空岛凭据。

脚本异常项只返回监控服务已经截断的错误摘要，不返回堆栈、数据库连接信息或 Token。

### 总体状态

- `HEALTHY`：没有 `WARNING` 或 `CRITICAL` 异常；
- `WARNING`：存在 14 点后未登录、脚本失败、单台设备离线或普通长任务；
- `CRITICAL`：存在 26 点后未登录、全部设备离线、加急任务超过 2 小时或多个脚本任务卡住。

总体状态只用于总览展示，不触发新的 PushPlus/WxPusher 通知，避免与现有通知系统重复发送。

## 数据口径

### 统一时间

服务入口只调用一次 `GameDayClock.now()`，并把该 `now` 传给所有统计逻辑。`gameDay` 和 `gameDayStartedAt` 使用 `GameDayClock` 计算，禁止使用宿主机默认时区或前端本地时间推导游戏日。

所有日期时间字段使用带 `+08:00` 偏移的 RFC 3339 字符串，响应同时固定返回 `timeZone: "Asia/Shanghai"`。前端统一通过带 `timeZone: "Asia/Shanghai"` 的格式化工具展示，不能直接按浏览器所在时区解释“今日”、04:00 游戏日边界或任务开始时间。

聚合结果是同一逻辑时间的近实时快照，不承诺跨 MySQL 与进程内队列的强事务一致性。`generatedAt` 明确快照时间，前端不得把不同响应拼接成一个快照。

### 账号

- `eligibleDaily`：`delete=0`、`freeze=0`、`task_type=daily`、`expire_time >= now`；
- `loggedToday`：上述账号中 `DailyLoginService` 返回次数大于等于 1；
- `missingLogin`：`eligibleDaily - loggedToday`；
- `frozen`：未删除、未过期且 `freeze=1`；
- `expiringWithinSevenDays`：未删除且到期时间处于 `(now, now+7天]`；
- `coolingDown`：任务看板中截止时间晚于 `now` 的冷却账号去重数。

用户管理新增 `/showAccount?...&login=missing` 服务端筛选。`login=missing` 只接受 `missing` 这一已知值，并在数据库分页前加入与本节相同的有效日常账号条件，同时排除以下任一事实存在的账号：

1. 游戏日起点之后、未删除、`INFO`、来源非空且非 `SYSTEM`、标题包含“登录成功”的日志；
2. 游戏日起点之后完成的 `daily + NORMAL + COMPLETED` 分配历史。

是否“未登录”只关心上述事实是否存在，因此 SQL 使用两个 `NOT EXISTS`；`assignmentId` 去重不会改变该布尔结果。该查询返回正确的完整集合 `total` 后再分页，页面内 `todayLoginCount === 0` 只能用于展示，不能用于筛选。Mapper 查询、总览统计和 `DailyLoginService` 必须有同一组固定时间契约测试，防止口径漂移。

### 任务

任务摘要和排序必须来自与任务管理相同的任务看板投影。为避免 15 秒轮询产生写副作用，需把 `TaskBoardService` 的“恢复过期冷却”与“构建只读快照”拆开：现有 `getBoard(now)` 可继续先恢复再读取；新增 `getReadOnlySnapshot(now)` 只能复制等待队列、分配和冷却映射，并在副本上过滤已经过期的冷却项。

总览严禁直接或间接调用 `TaskBoardService.getBoard()`、`TaskServiceImpl.restoreExpiredCooldownTasks()`、`getActiveCooldownTaskMap()` 或 `getActiveCooldownTaskInfoMap()`。为只读快照新增的冷却读取入口必须接收同一个 `now`，不得删除映射、调用 `dispatchQueueService.restoreBest()`、恢复/重排账号或执行数据库写入。

`longRunning` 的边界为 `runningMinutes >= 120`。不同来源的同一账号仍以当前有效分配或等待意图计一次。

### 设备

设备总数只统计 `device.delete=0`。后端新增共享的 `DeviceRuntimeProjectionService`，一次批量读取设备、`device_runtime` 和活动分配，以同一个 `now` 生成总览及设备管理页共同使用的投影。

每台设备只有一个主状态，按以下顺序判定：

1. `OFFLINE`：运行时状态不是 `ONLINE`，或最后心跳超过 30 分钟；
2. `SUSPENDED`：心跳有效且 `suspended_until > now`；
3. `BUSY`：心跳有效、未暂停且存在活动任务分配；
4. `IDLE`：心跳有效、未暂停且没有活动分配。

`online = IDLE + BUSY + SUSPENDED`，`total = online + offline`。设备管理使用的 `/showLoadedDevice` 在保留完整 Token 和旧整数 `status` 字段以兼容既有管理操作的同时，新增 `runtimeState`、`lastHeartbeatAt`、`offlineSince`、`suspendedUntil` 和当前账号摘要；页面展示和 `state` 筛选只使用新投影字段。总览响应仍只返回 Token 后 4 位。

不得继续使用 `DynamicInfo.deviceStatusMap` 的单一整数作为总览或设备管理页的在线事实来源。

### 脚本任务

直接复用 `ScheduledTaskMonitorService.getOverview(now)` 的数量和状态，不执行任务、不修改状态、不重新计算 cron。

### 经营数据

经营统计改用数据库聚合查询：`COUNT` 和 `SUM`，不再把所有账号或账单行加载到 Java 后再计数。空收入使用 `0.0`，时间边界使用 `Asia/Shanghai`。

第一阶段保留旧 `/getStatistics` 实现；新总览只使用新聚合服务。

## 后端结构

新增：

- `DashboardController`：管理员鉴权和协议转换；
- `AdminDashboardOverviewService`：以一个 `now` 编排各只读数据源；
- `AdminDashboardOverviewVO` 及账号、任务、设备、脚本、经营和异常子 VO；
- 面向经营数据的聚合 Mapper 查询；
- `AccountMapper` 的未登录账号服务端分页查询及对应总数查询；
- 任务看板和冷却映射的纯只读快照入口；
- `DeviceRuntimeProjectionService` 及设备运行时、活动分配的批量查询；
- `/showLoadedDevice` 的兼容扩展字段，旧 `status` 和完整 Token 不删除、不改名。

服务不得：

- 在一个数据库事务中包住整个总览；
- 在轮询请求中调用外部 HTTP、森空岛、邮件或推送；
- 触发任务恢复、任务分配、设备回收或脚本执行；
- 逐账号或逐设备执行 N+1 查询。

当前规模下接口查询预算为不超过 10 条有界 SQL，生产目标为 p95 小于 500ms。所有账号、设备和任务关联必须批量读取。

## 前端结构

新增：

- `lib/admin-dashboard.ts`：响应类型、状态映射、时间格式和刷新策略；
- `components/admin-dashboard/overview-metrics.tsx`；
- `components/admin-dashboard/alert-strip.tsx`；
- `components/admin-dashboard/task-snapshot.tsx`；
- `components/admin-dashboard/device-snapshot.tsx`；
- `components/admin-dashboard/account-progress.tsx`；
- `components/admin-dashboard/scheduled-task-health.tsx`；
- `components/admin-dashboard/business-summary.tsx`。

`app/admin/dashboard/page.tsx` 只负责鉴权、请求、刷新调度和区块组合，避免继续增长为单个超大组件。

继续使用现有 shadcn/ui 和 Lucide 图标，不新增图表依赖或状态管理库。

配套修改范围还包括：

- `app/api/showAccount/route.ts`：透传已校验的 `login=missing`；
- `app/admin/users/page.tsx`：从 URL 初始化“今日未登录”筛选，交给服务端分页，不做当前页过滤；
- `app/admin/tasks/page.tsx`：从 URL 初始化页签，并在页签变化时同步 URL；
- `app/admin/devices/page.tsx`：使用新的 `runtimeState` 展示和筛选，并支持 `state=offline`；
- `app/admin/scheduled-tasks/page.tsx`：从 URL 初始化状态筛选，并在筛选变化时同步 URL；
- `components/dashboard-layout.tsx`：增加可选内容宽度，总览使用 1600px，其余页面保持原值；
- `app/admin/settings/page.tsx`：新增“账号安全”区块并承接修改管理员密码。

## 刷新与页面状态

### 初次加载

显示固定尺寸骨架，六个指标和各区块保持最终布局尺寸。不得显示“加载中...”文字替代大数字，以免布局跳动。

### 后台刷新

- 间隔 15 秒；
- 页面隐藏时暂停轮询，重新可见时立即刷新；
- 使用 in-flight 标记或 `AbortController` 防止请求重叠；
- 手动刷新与自动刷新共用同一个请求函数；
- 后台刷新不清空当前数据，不改变滚动位置。

### 失败与过期

- 初次请求失败：显示页面级错误和重试按钮；
- 已有成功数据后刷新失败：保留旧快照，标题栏显示“数据已过期”和最后成功时间；
- 认证失败：沿用现有登录失效流程；
- 下一次成功刷新后自动清除过期状态。

## 深链

总览链接使用以下查询参数：

- `/admin/users?login=missing`；
- `/admin/tasks?tab=inProgress`；
- `/admin/devices?state=offline`；
- `/admin/scheduled-tasks?filter=ABNORMAL`。

对应页面需要通过 `useSearchParams` 读取查询参数设置当前筛选，并通过 `router.replace` 在用户切换页签/筛选时更新 URL。监听查询参数变化以支持浏览器前进、后退；未知值忽略并回退到默认视图，保留同一页面其他已知查询参数。用户管理的 `login=missing` 必须传到后端分页接口，不能在当前页做二次过滤。

## 安全与隐私

- 新接口必须是管理员专用 `@Login`；
- 生产发布必须确认解析后的 `inquisition.dev_mode=false`（环境变量为 `INQUISITION_DEV_MODE=false`），避免开发模式绕过 JWT 拦截；
- 响应不包含账号密码、管理员密码、完整设备 Token、森空岛凭据、通知 Token、邮件凭据或任务完整配置；
- 错误摘要使用现有截断和脱敏结果；
- 前端不得把 JWT 或接口完整响应写入 console；
- 修改密码迁移后仍使用 `type=password`，保存期间禁用重复提交，成功后清空密码字段。

## 测试设计

### 后端

- 固定 `now` 验证 04:00 游戏日边界；
- 验证有效、冻结、到期和非日常账号口径；
- 验证登录日志与完整日常完成记录按 `assignmentId` 去重；
- 验证 14 点和 26 点前后未登录异常级别；
- 验证加急、定时、手动和普通任务排序及前 5 条截断；
- 验证 120 分钟边界；
- 验证在线、心跳过期、忙碌、空闲、暂停和离线设备；
- 验证 `/showAccount?login=missing` 在过滤前计算完整 `total` 并正确分页，且结果与总览未登录数量一致；
- 验证 `/showLoadedDevice` 与总览对同一批运行时记录生成完全一致的主状态，旧字段仍可反序列化；
- 验证脚本任务状态透传；
- 验证经营统计空值为 0；
- 验证普通用户和代理用户不能访问管理员接口；
- 序列化测试确认响应不包含密码、完整 Token 和凭据字段；
- 连续读取总览前后断言等待队列、冷却截止、冷却原因和分配集合不变，并验证未调用恢复队列或数据库写入；
- MySQL 集成测试确认聚合 SQL 和索引路径；
- 现有任务、设备、脚本和旧统计接口回归通过；
- 使用本地夹具做 `/heartBeat`、`/getTask`、`/completeTask`、`/failTask` 协议 smoke regression，确认请求参数、设备响应和旧客户端行为没有变化。

### 前端

- 继续使用现有 `node --test` 覆盖状态、百分比、Asia/Shanghai 时间格式、URL 参数解析和轮询状态机纯函数，不引入 Vitest/JSDOM；
- 使用 Playwright 页面 smoke 覆盖正常、有异常、空列表、初次加载、旧数据过期、15 秒轮询、页面隐藏暂停和请求不重叠；
- Playwright 验证四个深链、筛选切换、刷新及浏览器前进后退；
- 修改密码迁移后原提交行为保持；
- 桌面 1920x1080、普通笔记本 1366x768、平板和手机截图验收；
- 检查所有数字、长账号名称、设备名称和错误文本不溢出；
- 检查键盘焦点、按钮名称和颜色之外的状态表达。

### 线上验收

在同一分钟内对照：

- 总览账号数量与用户管理筛选结果；
- 总览任务数量与任务管理；
- 总览设备状态与设备运行时及任务分配；
- 总览脚本数量与脚本任务页面；
- 总览经营数据与数据库聚合结果。

接口响应目标小于 500ms，15 秒轮询期间 MySQL 无明显慢查询和连接增长，后端日志无 ERROR。线上还需用普通用户 JWT 和无 JWT 请求确认新接口被拒绝，并确认生产 `inquisition.dev_mode=false`。

## 发布与回滚

1. 发布前确认生产 `INQUISITION_DEV_MODE=false`，再发布包含新接口且保留旧接口的后端；
2. 分别使用管理员 JWT、普通用户 JWT 和无 JWT 请求验证接口权限，再核对结构和数据口径；
3. 发布前端并由 EdgeOne 自动部署；
4. 对照各管理页面进行线上验收；
5. 观察接口耗时、错误日志和数据库查询；
6. 出现问题时先回滚前端，旧总览继续使用 `/getStatistics`；
7. 确认没有新前端流量后再回滚后端。

本设计不包含数据库迁移，因此代码回滚不会改变现有数据。

## 完成标准

- 管理员首屏能看见账号登录、任务、设备、脚本和异常状态；
- 所有状态来自真实后端数据，不存在写死的健康文案；
- 账号、任务、设备和脚本口径与对应管理页面一致；
- 四个总览深链均进入正确筛选，服务端分页总数与总览数量一致；
- 聚合接口单次返回同一逻辑时间快照；
- 重复轮询不会恢复冷却、重排队列或修改任务状态；
- 自动刷新不重叠、不清空旧数据、不覆盖用户上下文；
- 修改密码已经迁移到其他设置且功能回归通过；
- 桌面和移动端无重叠、溢出或不可操作控件；
- 后端、前端、真实 MySQL 和线上页面均有验证证据；
- 旧接口、旧前端和设备协议保持兼容。
