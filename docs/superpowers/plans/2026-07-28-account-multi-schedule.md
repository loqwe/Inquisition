# Account Multi-Schedule Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow one daily account to run at one to three configured times on its existing active weekdays without changing the device payload.

**Architecture:** Keep one account-level due pointer and one active scheduled run per account. Persist normalized times in `account_dispatch_time`, retain legacy single-time columns as a rollback summary, and leave an overdue pointer unchanged while another run is active.

**Tech Stack:** Java 11, Spring Boot, MyBatis Plus, MySQL 8, JUnit 5, Mockito, Next.js 15, React 19, TypeScript, Node test runner, Tailwind CSS.

---

### Task 1: MySQL v2 migration

**Files:**
- Create: `src/main/resources/db/manual/mysql-account-multi-schedule-v2.sql`
- Create: `src/main/resources/db/manual/mysql-account-multi-schedule-v2-rollback.sql`
- Create: `src/test/java/moe/dazecake/inquisition/MysqlAccountMultiScheduleMigrationTest.java`

- [ ] Add a failing Testcontainers test asserting table shape, `(account_id, schedule_time)` uniqueness, legacy-row backfill, idempotent rerun and rollback.
- [ ] Run `gradlew.bat test --tests moe.dazecake.inquisition.MysqlAccountMultiScheduleMigrationTest` and confirm the migration resource is missing.
- [ ] Add the forward and rollback SQL with `utf8mb4_0900_ai_ci` and schema assertions.
- [ ] Re-run the focused migration test and confirm it passes against MySQL 8.

### Task 2: Time persistence and calculation

**Files:**
- Create: `src/main/java/moe/dazecake/inquisition/model/entity/AccountDispatchTimeEntity.java`
- Create: `src/main/java/moe/dazecake/inquisition/mapper/AccountDispatchTimeMapper.java`
- Modify: `src/main/java/moe/dazecake/inquisition/service/impl/AccountScheduleCalculator.java`
- Test: `src/test/java/moe/dazecake/inquisition/service/impl/AccountScheduleCalculatorTest.java`

- [ ] Add failing tests for selecting the earliest occurrence from `[08:00, 14:00, 19:30]`, strict-after behavior, cross-day behavior and invalid empty input.
- [ ] Add `nextOccurrence(AccountEntity, Collection<LocalTime>, LocalDateTime)` and keep the single-time overload delegating to it.
- [ ] Add mapper operations to select sorted times, delete by account and persist replacement rows transactionally.
- [ ] Run calculator tests and compile the mapper/entity layer.

### Task 3: Compatible administrator configuration

**Files:**
- Modify: `src/main/java/moe/dazecake/inquisition/model/dto/account/AccountDispatchConfigDTO.java`
- Modify: `src/main/java/moe/dazecake/inquisition/model/vo/account/AccountWithSanVO.java`
- Modify: `src/main/java/moe/dazecake/inquisition/controller/AccountController.java`
- Modify: `src/main/java/moe/dazecake/inquisition/service/impl/AccountDispatchConfigService.java`
- Modify: `src/main/java/moe/dazecake/inquisition/service/impl/AccountServiceImpl.java`
- Test: `src/test/java/moe/dazecake/inquisition/controller/AccountControllerTest.java`
- Test: `src/test/java/moe/dazecake/inquisition/service/impl/AccountDispatchConfigServiceTest.java`
- Test: `src/test/java/moe/dazecake/inquisition/service/impl/AccountServiceImplTest.java`

- [ ] Add failing tests for `scheduleTimes`, legacy `scheduleTime`, 1-3 validation, sorting, duplicate rejection, pending activation and list hydration.
- [ ] Extend request validation to allow only `dispatchMode`, `scheduleTime` and `scheduleTimes`.
- [ ] Replace persisted time rows in the same transaction as the config update and synchronize the legacy first-time field.
- [ ] Return sorted `scheduleTimes` while keeping `scheduleTime` and `nextScheduledAt`.
- [ ] Run the three focused test classes.

### Task 4: Due-pointer scheduling and backlog preservation

**Files:**
- Modify: `src/main/java/moe/dazecake/inquisition/mapper/AccountDispatchConfigMapper.java`
- Modify: `src/main/java/moe/dazecake/inquisition/service/impl/AccountScheduledDispatchProcessor.java`
- Modify: `src/main/java/moe/dazecake/inquisition/service/impl/AccountScheduledRunLifecycleService.java`
- Test: `src/test/java/moe/dazecake/inquisition/service/impl/AccountScheduledDispatchProcessorTest.java`
- Test: `src/test/java/moe/dazecake/inquisition/service/impl/AccountScheduledRunLifecycleServiceTest.java`

- [ ] Add a failing test proving a due pointer remains unchanged while another scheduled run is active.
- [ ] Add a failing test proving successful occurrence creation advances to the earliest later configured time immediately.
- [ ] Add a failing test proving completion no longer overwrites an already-computed future pointer.
- [ ] Implement compare-and-set pointer advancement and load sorted times under the account transaction.
- [ ] Keep pending mode activation behavior unchanged and run both focused test classes.

### Task 5: Queue and device-contract regression

**Files:**
- Modify only if tests require: `src/main/java/moe/dazecake/inquisition/service/impl/DispatchQueueService.java`
- Test: `src/test/java/moe/dazecake/inquisition/service/impl/DispatchQueueServiceTest.java`
- Test: `src/test/java/moe/dazecake/inquisition/controller/LegacyClientHttpContractTest.java`

- [ ] Add a queue test with one account and multiple due occurrences proving one queue ID and oldest-run resolution.
- [ ] Re-run the complete JSON-tree equality test for AUTO, MANUAL and SCHEDULED normal assignments.
- [ ] Make the smallest queue change only if the new backend tests expose a regression.

### Task 6: Frontend data helpers

**Files:**
- Modify: `D:/自建功能/审判庭/_worktrees/inquisition-panel-multi-schedule/lib/account-dispatch.ts`
- Modify: `D:/自建功能/审判庭/_worktrees/inquisition-panel-multi-schedule/lib/account-dispatch.test.mjs`

- [ ] Add failing tests for fallback from one legacy time, sorting, duplicate detection, maximum three values, payload shape and multi-time task labels.
- [ ] Implement `normalizeScheduleTimes`, multi-time payload construction and validation.
- [ ] Run `pnpm run test:accounts` and confirm all helper tests pass.

### Task 7: Responsive multi-time editor

**Files:**
- Create: `D:/自建功能/审判庭/_worktrees/inquisition-panel-multi-schedule/components/account-schedule-editor.tsx`
- Modify: `D:/自建功能/审判庭/_worktrees/inquisition-panel-multi-schedule/components/user-edit-dialog.tsx`
- Modify: `D:/自建功能/审判庭/_worktrees/inquisition-panel-multi-schedule/app/admin/users/page.tsx`

- [ ] Replace single `scheduleTime` state with sorted `scheduleTimes` initialized from new or legacy fields.
- [ ] Add stable add/remove icon controls, one-to-three validation, desktop three-column layout, mobile single-column layout and a real next-run summary.
- [ ] Keep the existing active-weekday editor and save flow.
- [ ] Run all 17 existing frontend tests plus the new account helper tests.

### Task 8: Deployment-config and full verification

**Files:**
- Modify if required by clean install evidence: `D:/自建功能/审判庭/_worktrees/inquisition-panel-multi-schedule/pnpm-workspace.yaml`

- [ ] Reproduce a clean `pnpm install --frozen-lockfile` and ensure `sharp` is allowed without ignored-build failure.
- [ ] Run backend full tests with JDK 11 and `bootJar`.
- [ ] Run frontend tests, production build and TypeScript diagnostics; separate pre-existing diagnostics from new failures.
- [ ] Run MySQL 8 migration, rerun, rollback and reapply.
- [ ] Verify desktop and mobile screenshots show no incoherent empty area, overlap or clipped text.
- [ ] Inspect final diffs, confirm both worktrees are limited to this feature, and record deploy/rollback steps without pushing or deploying.
