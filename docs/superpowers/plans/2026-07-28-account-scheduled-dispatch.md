# Account Scheduled Dispatch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Add per-account AUTO/SCHEDULED dispatch so selected daily accounts run their unchanged full daily payload at one configured time on enabled weekdays, with persistent high-priority recovery and no legacy device JSON changes.

**Architecture:** Keep `DynamicInfo.waitUserList` as the account-order compatibility layer, but make a new `DispatchQueueService` its only writer and persist scheduled configuration/runs in MySQL. Store dispatch source only on server-side assignment rows, keep scheduling data outside `AccountEntity`, and expose it only through administrator VOs.

**Tech Stack:** Java 11, Spring Boot 2.7, MyBatis-Plus, MySQL 8, JUnit 5/Mockito, Next.js 15, React 19, TypeScript, Node test runner.

---

### Task 1: Persistence contract

**Files:**
- Create: `src/main/java/moe/dazecake/inquisition/model/entity/AccountDispatchConfigEntity.java`
- Create: `src/main/java/moe/dazecake/inquisition/model/entity/AccountScheduledRunEntity.java`
- Create: `src/main/java/moe/dazecake/inquisition/mapper/AccountDispatchConfigMapper.java`
- Create: `src/main/java/moe/dazecake/inquisition/mapper/AccountScheduledRunMapper.java`
- Modify: `src/main/java/moe/dazecake/inquisition/model/entity/TaskAssignmentEntity.java`
- Modify: `src/main/java/moe/dazecake/inquisition/model/entity/TaskAssignmentHistoryEntity.java`
- Create: `src/main/resources/db/manual/mysql-account-scheduled-dispatch-v1.sql`
- Create: `src/main/resources/db/manual/mysql-account-scheduled-dispatch-v1-rollback.sql`
- Test: `src/test/java/moe/dazecake/inquisition/MysqlAccountScheduledDispatchMigrationTest.java`

- [x] **Step 1: Write the failing migration contract test**

Assert that the forward migration creates both tables, the unique `(account_id, scheduled_for)` key, dispatch indexes, and `dispatch_source`/`scheduled_run_id` columns on active and history assignments. Assert rollback only removes these artifacts.

- [x] **Step 2: Run the migration test and verify RED**

Run: `./gradlew test --tests moe.dazecake.inquisition.MysqlAccountScheduledDispatchMigrationTest`

Expected: FAIL because the two SQL resources do not exist.

- [x] **Step 3: Add the minimal entities, mappers, and idempotent MySQL 8 SQL**

Use these server-only fields:

```java
class AccountDispatchConfigEntity {
    Long accountId;
    String dispatchMode;
    LocalTime scheduleTime;
    LocalDateTime nextScheduledAt;
    Integer activationPending;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
}

class AccountScheduledRunEntity {
    Long id;
    Long accountId;
    LocalDateTime scheduledFor;
    LocalDate gameDay;
    String status;
    Integer attemptCount;
    LocalDateTime nextRetryAt;
    String lastError;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
    LocalDateTime finishedAt;
}
```

Do not add scheduling fields to `AccountEntity` or `AccountDTO`.

- [x] **Step 4: Re-run the focused test and verify GREEN**

Expected: PASS.

- [x] **Step 5: Commit persistence**

```bash
git add src/main/java/moe/dazecake/inquisition/model/entity src/main/java/moe/dazecake/inquisition/mapper src/main/resources/db/manual src/test/java/moe/dazecake/inquisition/MysqlAccountScheduledDispatchMigrationTest.java
git commit -m "feat: add scheduled dispatch persistence"
```

### Task 2: Schedule calculation and configuration lifecycle

**Files:**
- Create: `src/main/java/moe/dazecake/inquisition/service/impl/AccountScheduleCalculator.java`
- Create: `src/main/java/moe/dazecake/inquisition/service/impl/AccountDispatchConfigService.java`
- Create: `src/main/java/moe/dazecake/inquisition/model/dto/account/AccountDispatchConfigDTO.java`
- Test: `src/test/java/moe/dazecake/inquisition/service/impl/AccountScheduleCalculatorTest.java`
- Test: `src/test/java/moe/dazecake/inquisition/service/impl/AccountDispatchConfigServiceTest.java`

- [x] **Step 1: Write failing pure time tests**

Cover Asia/Shanghai wall time, enabled weekdays, same-game-day catch-up, old-game-day skip, completion after missed days, and no enabled weekday validation.

Desired API:

```java
LocalDateTime nextOccurrence(AccountEntity account, LocalTime time, LocalDateTime strictlyAfter);
boolean belongsToCurrentGameDay(LocalDateTime scheduledFor, LocalDateTime now);
```

- [x] **Step 2: Run calculator tests and verify RED**

Run: `./gradlew test --tests moe.dazecake.inquisition.service.impl.AccountScheduleCalculatorTest`

Expected: FAIL because the calculator is missing.

- [x] **Step 3: Implement the calculator and verify GREEN**

Use `GameDayClock.ZONE_ID` semantics and the account's existing `ActivationDate` booleans. A returned occurrence must be strictly later than the supplied boundary.

- [x] **Step 4: Write failing configuration transition tests**

Cover absent row equals AUTO, AUTO-to-SCHEDULED next future time, active assignment sets `activationPending=1`, SCHEDULED-to-AUTO clears next time, and pending activation after assignment closure.

- [x] **Step 5: Implement configuration service and verify GREEN**

Expose:

```java
AccountDispatchConfigEntity getOrDefault(Long accountId);
boolean isAuto(Long accountId);
void update(AccountEntity account, AccountDispatchConfigDTO request, boolean assignmentActive, LocalDateTime now);
void activatePending(AccountEntity account, LocalDateTime now);
```

- [x] **Step 6: Commit configuration lifecycle**

```bash
git add src/main/java/moe/dazecake/inquisition/service/impl/AccountScheduleCalculator.java src/main/java/moe/dazecake/inquisition/service/impl/AccountDispatchConfigService.java src/main/java/moe/dazecake/inquisition/model/dto/account/AccountDispatchConfigDTO.java src/test/java/moe/dazecake/inquisition/service/impl/AccountScheduleCalculatorTest.java src/test/java/moe/dazecake/inquisition/service/impl/AccountDispatchConfigServiceTest.java
git commit -m "feat: add account schedule configuration"
```

### Task 3: Persistent scheduled-run state machine and monitored scanner

**Files:**
- Create: `src/main/java/moe/dazecake/inquisition/service/impl/AccountScheduledRunService.java`
- Create: `src/main/java/moe/dazecake/inquisition/service/impl/AccountScheduledDispatchService.java`
- Modify: `src/main/java/moe/dazecake/inquisition/utils/DynamicScheduleTask.java`
- Modify: `src/main/java/moe/dazecake/inquisition/utils/RunScript.java`
- Test: `src/test/java/moe/dazecake/inquisition/service/impl/AccountScheduledRunServiceTest.java`
- Test: `src/test/java/moe/dazecake/inquisition/service/impl/AccountScheduledDispatchServiceTest.java`
- Test: `src/test/java/moe/dazecake/inquisition/utils/DynamicScheduleTaskTest.java`
- Test: `src/test/java/moe/dazecake/inquisition/utils/RunScriptTest.java`

- [x] **Step 1: Write failing state-machine tests**

Cover one active instance per account, WAITING/RUNNING/RETRY_WAIT/SUCCEEDED/CANCELLED/FAILED transitions, attempt count, early retry by immediate-start, and idempotent creation for one scheduled timestamp.

- [x] **Step 2: Verify state-machine RED, implement, and verify GREEN**

Run: `./gradlew test --tests moe.dazecake.inquisition.service.impl.AccountScheduledRunServiceTest`

- [x] **Step 3: Write failing scanner tests**

Cover due occurrence creation, same-game-day catch-up, old-game-day advancement without creation, active-run suppression across 04:00, frozen/deleted/expired skip, and startup restoration.

- [x] **Step 4: Implement scanner and one-minute monitored Cron**

Register:

```text
key=account-scheduled-dispatch
cron=0 */1 * * * *
timezone=Asia/Shanghai
```

Feature flag: `inquisition.accountSchedule.enabled`.

- [x] **Step 5: Run focused scanner/scheduler/startup tests**

Expected: PASS and exactly one new monitored task definition.

- [x] **Step 6: Commit scheduled-run lifecycle**

```bash
git add src/main/java/moe/dazecake/inquisition/service/impl/AccountScheduledRunService.java src/main/java/moe/dazecake/inquisition/service/impl/AccountScheduledDispatchService.java src/main/java/moe/dazecake/inquisition/utils/DynamicScheduleTask.java src/main/java/moe/dazecake/inquisition/utils/RunScript.java src/test/java/moe/dazecake/inquisition
git commit -m "feat: schedule persistent account runs"
```

### Task 4: Central queue admission and priority

**Files:**
- Create: `src/main/java/moe/dazecake/inquisition/service/impl/DispatchQueueService.java`
- Create: `src/main/java/moe/dazecake/inquisition/model/local/DispatchIntent.java`
- Modify: `src/main/java/moe/dazecake/inquisition/service/impl/AccountRuntimeService.java`
- Modify: `src/main/java/moe/dazecake/inquisition/service/impl/AccountServiceImpl.java`
- Modify: `src/main/java/moe/dazecake/inquisition/service/impl/DailyLoginSweepService.java`
- Modify: `src/main/java/moe/dazecake/inquisition/service/impl/FinalLoginSweepService.java`
- Modify: `src/main/java/moe/dazecake/inquisition/service/impl/ProUserServiceImpl.java`
- Modify: `src/main/java/moe/dazecake/inquisition/service/impl/TaskBoardService.java`
- Modify: `src/main/java/moe/dazecake/inquisition/service/impl/TaskRecoveryService.java`
- Modify: `src/main/java/moe/dazecake/inquisition/service/impl/TaskServiceImpl.java`
- Modify: `src/main/java/moe/dazecake/inquisition/utils/DynamicScheduleTask.java`
- Modify: `src/main/java/moe/dazecake/inquisition/utils/RunScript.java`
- Test: `src/test/java/moe/dazecake/inquisition/service/impl/DispatchQueueServiceTest.java`
- Update focused tests for every modified producer.

- [x] **Step 1: Write failing queue tests**

Assert priority `URGENT_26 > SCHEDULED = MANUAL > AUTO`, FIFO inside the high tier, unchanged AUTO order, one ID per account, stale SCHEDULED-as-AUTO rejection, and simultaneous urgent plus scheduled intent restoration.

Desired entry points:

```java
boolean enqueueAuto(Long accountId);
boolean enqueueManual(Long accountId);
boolean enqueueScheduled(AccountScheduledRunEntity run);
boolean enqueueUrgent(Long accountId);
void requeue(TaskAssignmentEntity assignment);
void remove(Long accountId);
void reconcileRestoredQueue(LocalDateTime now);
DispatchIntent resolve(Long accountId, LocalDateTime now);
```

- [x] **Step 2: Verify RED, implement queue service, verify GREEN**

Run: `./gradlew test --tests moe.dazecake.inquisition.service.impl.DispatchQueueServiceTest`

- [x] **Step 3: Replace every direct wait-list writer**

Use `rg -n "getWaitUserList\\(\\).*(add|remove|clear)|setWaitUserList" src/main/java` as the completion inventory. Reads may remain; production writes must be confined to `DispatchQueueService` and `DynamicInfo.load`.

- [x] **Step 4: Add regression tests for each producer**

Prove sanity and 14:00 do not auto-enqueue SCHEDULED accounts; 26:00 still enqueues urgent; manual start is high priority; force-load rebuilds only AUTO while preserving scheduled/urgent; cooldown and offline restoration preserve the resolved source.

- [x] **Step 5: Run all queue-related focused tests**

Expected: PASS, and the writer inventory contains no business-service direct mutation.

- [x] **Step 6: Commit queue centralization**

```bash
git add src/main/java/moe/dazecake/inquisition/service/impl src/main/java/moe/dazecake/inquisition/model/local src/main/java/moe/dazecake/inquisition/utils src/test/java/moe/dazecake/inquisition
git commit -m "refactor: centralize task queue admission"
```

### Task 5: Assignment lifecycle and legacy device payload

**Files:**
- Modify: `src/main/java/moe/dazecake/inquisition/service/impl/TaskAssignmentService.java`
- Modify: `src/main/java/moe/dazecake/inquisition/service/impl/TaskServiceImpl.java`
- Modify: `src/main/java/moe/dazecake/inquisition/service/impl/TaskRecoveryService.java`
- Modify: `src/main/java/moe/dazecake/inquisition/model/vo/task/TaskBoardAccountVO.java`
- Modify: `src/main/java/moe/dazecake/inquisition/model/vo/task/RunningTaskVO.java`
- Test: `src/test/java/moe/dazecake/inquisition/service/impl/TaskAssignmentServiceTest.java`
- Test: `src/test/java/moe/dazecake/inquisition/service/impl/TaskServiceImplTest.java`
- Test: `src/test/java/moe/dazecake/inquisition/service/impl/TaskRecoveryServiceTest.java`
- Test: `src/test/java/moe/dazecake/inquisition/controller/LegacyClientHttpContractTest.java`

- [x] **Step 1: Write failing source-aware assignment tests**

Assert assignment/history persist `dispatchSource` and `scheduledRunId`; scheduled complete marks SUCCEEDED; failure/offline/timeout preserve one run; pending mode switch prevents old-source requeue; urgent completion restores an underlying scheduled run.

- [x] **Step 2: Write the full JSON compatibility test and verify RED**

Serialize one fixed account through AUTO, MANUAL, and SCHEDULED NORMAL assignments. Remove only `assignmentId`, then assert complete JSON-tree equality and absence of all dispatch fields.

- [x] **Step 3: Implement source-aware locking and lifecycle hooks**

Pass `DispatchIntent` into assignment creation, retain `MODE_NORMAL` for scheduled work, and update scheduled state only on the server.

- [x] **Step 4: Run focused lifecycle and legacy tests**

Expected: all PASS; the existing LOGIN_ONLY tests remain unchanged and green.

- [x] **Step 5: Commit assignment lifecycle**

```bash
git add src/main/java/moe/dazecake/inquisition/service/impl src/main/java/moe/dazecake/inquisition/model/vo/task src/test/java/moe/dazecake/inquisition
git commit -m "feat: preserve dispatch source through task recovery"
```

### Task 6: Administrator account API

**Files:**
- Modify: `src/main/java/moe/dazecake/inquisition/controller/AccountController.java`
- Modify: `src/main/java/moe/dazecake/inquisition/service/intf/AccountService.java`
- Modify: `src/main/java/moe/dazecake/inquisition/service/impl/AccountServiceImpl.java`
- Modify: `src/main/java/moe/dazecake/inquisition/model/vo/account/AccountWithSanVO.java`
- Modify: `src/main/java/moe/dazecake/inquisition/mapper/mapstruct/AccountConvert.java`
- Test: `src/test/java/moe/dazecake/inquisition/service/impl/AccountServiceImplTest.java`
- Test: `src/test/java/moe/dazecake/inquisition/controller/AccountControllerTest.java`

- [x] **Step 1: Write failing API/service tests**

Cover optional nested `dispatchConfig`, old request omission preserving prior config, validation errors, transactional active-week/config update, list/search hydration, and no scheduling fields on `AccountDTO`.

- [x] **Step 2: Verify RED and implement the optional admin-only contract**

Request shape:

```json
{
  "id": 1,
  "active": { "monday": { "enable": true } },
  "dispatchConfig": { "dispatchMode": "SCHEDULED", "scheduleTime": "19:30" }
}
```

- [x] **Step 3: Run focused controller/service/MapStruct tests**

Expected: old account updates still pass and device DTO remains unchanged.

- [x] **Step 4: Commit administrator API**

```bash
git add src/main/java/moe/dazecake/inquisition/controller/AccountController.java src/main/java/moe/dazecake/inquisition/service src/main/java/moe/dazecake/inquisition/model/vo/account src/main/java/moe/dazecake/inquisition/mapper/mapstruct src/test/java/moe/dazecake/inquisition
git commit -m "feat: expose account dispatch settings to admins"
```

### Task 7: Administrator panel

**Files:**
- Create: `../inquisition-panel/lib/account-dispatch.ts`
- Create: `../inquisition-panel/lib/account-dispatch.test.mjs`
- Modify: `../inquisition-panel/package.json`
- Modify: `../inquisition-panel/components/user-edit-dialog.tsx`
- Modify: `../inquisition-panel/app/admin/users/page.tsx`
- Modify: `../inquisition-panel/lib/task-board.ts`
- Modify: `../inquisition-panel/lib/task-board.test.mjs`
- Modify: `../inquisition-panel/app/admin/tasks/page.tsx`

- [x] **Step 1: Write failing pure helper tests**

Cover exact labels:

```text
AUTO -> 日常任务
SCHEDULED -> 定时任务 / 19:30 / 正常
```

Also cover status mapping, request construction, and scheduled/manual task-source labels.

- [x] **Step 2: Run Node tests and verify RED**

Run: `pnpm test:accounts`

Expected: FAIL because helper/script do not exist.

- [x] **Step 3: Implement helpers and edit dialog**

Place an AUTO/SCHEDULED segmented control above the existing active-time card. Show a native time input only for SCHEDULED. Reuse existing weekday checkboxes and block save when time or weekdays are missing.

- [x] **Step 4: Update account and task displays**

Keep AUTO text exactly unchanged. Render SCHEDULED in the existing task-type cell only, and add a compact “定时” source badge to pending/running task rows without adding a tab.

- [x] **Step 5: Run helper tests, type/build checks, and inspect responsive layout**

Run: `pnpm test:accounts`, `pnpm test:tasks`, `pnpm test:scheduled`, `pnpm build`.

Expected: PASS with no text overflow at desktop and mobile widths.

- [x] **Step 6: Commit panel changes in the panel repository**

```bash
git add package.json lib components/user-edit-dialog.tsx app/admin/users/page.tsx app/admin/tasks/page.tsx
git commit -m "feat: configure scheduled account dispatch"
```

### Task 8: Full verification and local release evidence

**Files:**
- Update plan checkboxes as tasks complete.
- No deployment files unless verification exposes a required local configuration default.

- [x] **Step 1: Run complete backend verification**

Run: `./gradlew test` and `./gradlew bootJar`.

Expected: all tests pass and the JAR is produced.

- [x] **Step 2: Validate SQL against disposable MySQL 8**

Execute forward migration twice, rollback, then forward migration again. Verify indexes, defaults, history columns, transaction rollback, and utf8mb4 collation.

- [x] **Step 3: Run complete frontend verification**

Run all three Node test scripts and `pnpm build`.

- [x] **Step 4: Verify device payload snapshots**

Capture serialized AUTO and SCHEDULED NORMAL responses for the same account and compare parsed JSON after removing only `assignmentId`. Field names, types, values, and nested daily configuration must match.

- [x] **Step 5: Audit the final diffs**

Confirm no secrets, unrelated changes, direct wait-list writers, device DTO scheduling fields, deployment, or remote push. Record any residual limitations explicitly.

- [x] **Step 6: Report local completion**

Report backend/frontend commits, focused/full test counts, build outputs, migration evidence, and the explicit statement that nothing was deployed or pushed.

## Local verification evidence (2026-07-28)

- Backend full test run: 304 tests, 9 skipped, 0 failures/errors; `bootJar` produced `build/libs/Inquisition-1.3.1.jar`.
- Legacy device contract: 5 focused tests passed; AUTO, MANUAL, and SCHEDULED NORMAL payload trees are identical after removing only `assignmentId`.
- Frontend: `test:accounts` 7/7, `test:tasks` 7/7, `test:scheduled` 3/3, and `pnpm build` passed.
- Responsive UI: Playwright checked 1440x1000 and 390x844; the dialog had no horizontal overflow, the segmented labels fit, and pending/running source badges rendered.
- MySQL 8.4: forward migration, repeated forward migration, rollback, and reapply passed. Unique slots, transaction rollback, latest-run batch SQL, assignment/history defaults, indexes, and `utf8mb4_0900_ai_ci` were verified.
- Queue writer inventory contains only the permitted `DynamicInfo.load` restore path outside `DispatchQueueService`; no scheduling field is present in the device DTO.
- Repository-wide `tsc --noEmit` still reports the same two pre-existing baseline errors in `app/prouser/cdk/page.ts` and `components/daily-plan-editor.tsx`; this feature adds no new TypeScript diagnostic.
- Nothing was pushed or deployed. Production migration, feature-flag enablement, and real-device timing remain release-stage work.
