# Urgent Login Task Board Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist only the 26:00 urgent login work, preserve the legacy Lua HTTP payload, and display those urgent tasks above normal tasks in the existing three-tab administrator task page without changing the existing 14:00 behavior.

**Architecture:** Add a bounded `urgent_task` table and service as the source of truth for urgency while keeping `DynamicInfo.waitUserList` as the legacy normal queue. Persist the internal assignment mode on `task_assignment`, derive a single task-board snapshot for the panel, and keep all legacy endpoints and payload fields compatible.

**Tech Stack:** Java 11, Spring Boot 2.7, MyBatis-Plus, MySQL 8, JUnit 5/Mockito, Next.js 15, React 19, TypeScript, Node test runner.

---

### Task 1: Persistence contract

**Files:**
- Create: `src/main/java/moe/dazecake/inquisition/model/entity/UrgentTaskEntity.java`
- Create: `src/main/java/moe/dazecake/inquisition/mapper/UrgentTaskMapper.java`
- Modify: `src/main/java/moe/dazecake/inquisition/model/entity/TaskAssignmentEntity.java`
- Modify: `src/main/java/moe/dazecake/inquisition/model/entity/TaskAssignmentHistoryEntity.java`
- Create: `src/main/resources/db/manual/mysql-urgent-task-v1.sql`
- Create: `src/main/resources/db/manual/mysql-urgent-task-v1-rollback.sql`
- Test: `src/test/java/moe/dazecake/inquisition/MysqlUrgentTaskMigrationTest.java`

- [ ] Write migration tests asserting the table, unique game-day/account key, dispatch index, assignment mode columns, and rollback scope.
- [ ] Run `./gradlew test --tests moe.dazecake.inquisition.MysqlUrgentTaskMigrationTest` and confirm failure because resources do not exist.
- [ ] Add the entity, mapper, migration, rollback, and assignment fields.
- [ ] Re-run the focused test and confirm it passes.

### Task 2: Urgent lifecycle service

**Files:**
- Create: `src/main/java/moe/dazecake/inquisition/service/impl/UrgentTaskService.java`
- Test: `src/test/java/moe/dazecake/inquisition/service/impl/UrgentTaskServiceTest.java`

- [ ] Write failing tests for idempotent enqueue, 26:00 mode upgrade, ready ordering, assignment start, retry state, login completion, cancellation, and old-game-day cleanup.
- [ ] Run the focused test and confirm the missing service failure.
- [ ] Implement the smallest service API needed by scheduling and task dispatch.
- [ ] Re-run focused tests and keep terminal rows excluded from active queries.

### Task 3: 26:00 scheduling

**Files:**
- Create: `src/main/java/moe/dazecake/inquisition/service/impl/FinalLoginSweepService.java`
- Modify: `src/main/java/moe/dazecake/inquisition/utils/DynamicScheduleTask.java`
- Modify: `src/main/java/moe/dazecake/inquisition/utils/RunScript.java`
- Test: `src/test/java/moe/dazecake/inquisition/service/impl/FinalLoginSweepServiceTest.java`
- Test: `src/test/java/moe/dazecake/inquisition/utils/DynamicScheduleTaskTest.java`
- Test: `src/test/java/moe/dazecake/inquisition/utils/RunScriptTest.java`

- [ ] Add failing tests that 02:00 belongs to the previous game day and upgrades missing accounts to `LOGIN_ONLY` without interrupting running assignments.
- [ ] Add failing registration tests for the 02:00 scan, 03:45 summary, and 04:00 cleanup.
- [ ] Implement the services and monitored Cron definitions, then make startup recovery run both due scans.

### Task 4: Dispatch and legacy Lua compatibility

**Files:**
- Modify: `src/main/java/moe/dazecake/inquisition/service/impl/TaskAssignmentService.java`
- Modify: `src/main/java/moe/dazecake/inquisition/service/impl/TaskServiceImpl.java`
- Modify: `src/main/java/moe/dazecake/inquisition/service/impl/AccountRuntimeService.java`
- Modify: `src/main/java/moe/dazecake/inquisition/model/entity/ConfigEntitySet/Daily.java`
- Test: `src/test/java/moe/dazecake/inquisition/service/impl/TaskServiceImplTest.java`
- Test: `src/test/java/moe/dazecake/inquisition/service/impl/AccountRuntimeServiceTest.java`
- Test: `src/test/java/moe/dazecake/inquisition/controller/LegacyClientHttpContractTest.java`

- [ ] Add a failing dispatch test showing ready urgent work beats an administrator-inserted normal task.
- [ ] Add a failing payload test asserting `LOGIN_ONLY` still returns `taskType=daily`, empty fight list, and every daily action false without mutating the stored account.
- [ ] Add failing login-log tests for closing only a `LOGIN_ONLY` assignment and returning the account to the normal queue tail.
- [ ] Persist assignment mode/urgent id, implement dispatch selection and the temporary payload copy, then implement success/failure lifecycle hooks.

### Task 5: Task-board API

**Files:**
- Create: `src/main/java/moe/dazecake/inquisition/model/vo/task/TaskBoardVO.java`
- Create: `src/main/java/moe/dazecake/inquisition/model/vo/task/UrgentTaskVO.java`
- Create: `src/main/java/moe/dazecake/inquisition/model/vo/task/PendingTaskVO.java`
- Create: `src/main/java/moe/dazecake/inquisition/model/vo/task/RunningTaskVO.java`
- Create: `src/main/java/moe/dazecake/inquisition/service/impl/TaskBoardService.java`
- Modify: `src/main/java/moe/dazecake/inquisition/controller/TaskController.java`
- Test: `src/test/java/moe/dazecake/inquisition/service/impl/TaskBoardServiceTest.java`
- Test: `src/test/java/moe/dazecake/inquisition/controller/TaskControllerTest.java`

- [ ] Write failing tests that the snapshot excludes urgent accounts from normal pending, sorts urgent running assignments first, and returns consistent counts.
- [ ] Implement `GET /showTaskBoard`, `POST /retryUrgentTask`, and `POST /cancelUrgentTask` behind the existing administrator login guard.
- [ ] Keep `showFreeTaskList` and `showLockTaskList` unchanged for older panels.

### Task 6: Three-tab administrator UI

**Files:**
- Create: `../inquisition-panel/lib/task-board.ts`
- Create: `../inquisition-panel/lib/task-board.test.mjs`
- Modify: `../inquisition-panel/app/admin/tasks/page.tsx`
- Modify: `../inquisition-panel/package.json`

- [ ] Write failing Node tests for urgent-first grouping, stable normal order, mode/status labels, and 5/15 second refresh intervals.
- [ ] Implement shared task-board types and pure presentation helpers.
- [ ] Replace the page's split initial requests with the snapshot endpoint while preserving three tabs and existing controls.
- [ ] Render a compact summary strip, a visually separate urgent section, normal tasks below it, and urgent running rows first.
- [ ] Preserve old data after refresh errors and show the stale timestamp state.

### Task 7: Verification and release

- [ ] Run focused backend tests, then `./gradlew test` and `./gradlew bootJar`.
- [ ] Validate the forward and rollback SQL against the disposable MySQL 8 test container used by the deployment workflow.
- [ ] Run `pnpm test:tasks`, existing `pnpm test:scheduled`, and `pnpm build`.
- [ ] Start the panel locally and inspect `/admin/tasks` at desktop and mobile widths with non-empty fixture data.
- [ ] Review both diffs for credential leakage and unrelated changes.
- [ ] Commit backend and frontend separately, push their existing branches, deploy the backend to 129 with rollback artifacts, and verify health, migration, Cron registration, and task-board JSON.
