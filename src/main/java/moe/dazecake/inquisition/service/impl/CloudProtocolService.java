package moe.dazecake.inquisition.service.impl;

import com.google.gson.Gson;
import moe.dazecake.inquisition.model.dto.cloud.*;
import moe.dazecake.inquisition.utils.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CloudProtocolService {
    private static final Gson GSON = new Gson();
    @Autowired(required = false)
    private JdbcTemplate jdbc;
    private final Map<String, Device> devices = new ConcurrentHashMap<>();
    private final Map<String, CloudTask> tasks = new ConcurrentHashMap<>();

    public Result<Map<String, String>> register(CloudRegisterDTO req) {
        if (req == null || blank(req.getDeviceToken())) return Result.paramError("deviceToken required");
        String hash = sha256(req.getDeviceToken());
        Device d = devices.computeIfAbsent(hash, k -> new Device(
                UUID.nameUUIDFromBytes(k.getBytes(StandardCharsets.UTF_8)).toString()));
        d.name = req.getDeviceName(); d.version = req.getClientVersion(); d.lastHeartbeat = LocalDateTime.now();
        return Result.success(Map.of("deviceId", d.id), "registered");
    }

    public Result<Map<String, Object>> heartbeat(CloudHeartbeatDTO req) {
        Device d = device(req == null ? null : req.getDeviceToken());
        if (d == null) return Result.unauthorized("invalid device token");
        d.status = req.getStatus(); d.lastHeartbeat = LocalDateTime.now();
        return Result.success(Map.of("deviceId", d.id, "serverTime", d.lastHeartbeat.toString()), "ok");
    }

    public Result<String> reportGameName(CloudGameNameDTO req) {
        if (req == null || blank(req.getGameName()) || req.getAccountId() == null) {
            return Result.paramError("accountId and gameName required");
        }
        String gameName = req.getGameName().trim();
        if (gameName.length() > 128) return Result.paramError("gameName too long");

        CloudTask task = authorizedTask(req.getDeviceToken(), req.getTaskId(),
                req.getAttemptId(), req.getLeaseId());
        if (task == null || task.accountId == null || !task.accountId.equals(req.getAccountId())) {
            return Result.unauthorized("task authorization failed");
        }
        if (jdbc == null) return Result.failed("database unavailable");

        int changed = jdbc.update("UPDATE account SET game_name=?,update_time=CURRENT_TIMESTAMP WHERE id=? AND `delete`=0",
                gameName, task.accountId);
        if (changed == 0) {
            Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM account WHERE id=? AND `delete`=0",
                    Integer.class, task.accountId);
            if (existing == null || existing == 0) return Result.notFound("account not found");
        }
        return Result.success("game name updated");
    }

    public Result<CloudTask> getTask(String token) {
        Device d = device(token); if (d == null) return Result.unauthorized("invalid device token");
        if (jdbc != null) {
            CloudTask persisted = claimPersistedTask(d.id);
            if (persisted != null) return Result.success(persisted, "assigned");
        }
        for (CloudTask t : tasks.values()) {
            if ("RUNNING".equals(t.status) && t.leaseExpiresAt != null && t.leaseExpiresAt.isBefore(LocalDateTime.now())) {
                t.status = "READY"; t.deviceId = null; t.leaseId = null;
            }
            if ("READY".equals(t.status) && (t.deviceId == null || t.deviceId.equals(d.id))) {
                t.deviceId = d.id; t.status = "RUNNING"; t.leaseId = UUID.randomUUID().toString();
                t.leaseExpiresAt = LocalDateTime.now().plusMinutes(10); return Result.success(t, "assigned");
            }
        }
        return Result.success((CloudTask) null, "no task");
    }

    public Result<String> complete(CloudTaskReportDTO req) { return transition(req, "COMPLETED"); }
    public Result<String> fail(CloudTaskReportDTO req) { return transition(req, "FAILED"); }

    public Result<String> addLog(CloudLogDTO req) {
        CloudTask t = authorizedTask(req == null ? null : req.getDeviceToken(), req == null ? null : req.getTaskId(), req == null ? null : req.getAttemptId(), req == null ? null : req.getLeaseId());
        if (t == null) return Result.unauthorized("task authorization failed");
        t.logs.putIfAbsent(req.getSequenceNo(), req.getLevel() + ":" + req.getMessage());
        if (jdbc != null) {
            jdbc.update("INSERT IGNORE INTO run_log (task_run_id,account_id,sequence_no,level,phase,message,context_json,occurred_at) SELECT id,account_id,?,?,?,?,?,CURRENT_TIMESTAMP FROM task_run WHERE run_key=? AND attempt_id=? AND lease_id=?",
                    req.getLevel(), req.getPhase(), req.getMessage(), req.getContextJson(), req.getTaskId(), req.getAttemptId(), req.getLeaseId());
        }
        return Result.success("accepted");
    }

    public CloudTask enqueue(String taskId, Long accountId, Object config) {
        String snapshot = config instanceof String ? (String) config : GSON.toJson(config == null ? Map.of() : config);
        CloudTask t = new CloudTask(taskId, accountId, parseConfig(snapshot));
        if (jdbc != null) {
            jdbc.update("INSERT INTO task_run (account_id,run_key,attempt_id,status,trigger_type,config_snapshot,created_at,updated_at) VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    accountId, taskId, t.attemptId, "READY", "MANUAL", snapshot);
        }
        tasks.put(taskId, t);
        return t;
    }

    public boolean hasActiveTask(Long accountId) {
        if (accountId == null) return false;
        if (jdbc != null) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM task_run WHERE account_id=? AND status IN ('READY','RUNNING')",
                    Integer.class, accountId);
            return count != null && count > 0;
        }
        return tasks.values().stream().anyMatch(task -> accountId.equals(task.accountId)
                && ("READY".equals(task.status) || "RUNNING".equals(task.status)));
    }

    public String activeTaskStatus(Long accountId) {
        if (accountId == null) return null;
        if (jdbc != null) {
            var rows = jdbc.queryForList(
                    "SELECT status FROM task_run WHERE account_id=? AND status IN ('READY','RUNNING') ORDER BY created_at DESC LIMIT 1",
                    accountId);
            return rows.isEmpty() ? null : String.valueOf(rows.get(0).get("status"));
        }
        return tasks.values().stream()
                .filter(task -> accountId.equals(task.accountId)
                        && ("READY".equals(task.status) || "RUNNING".equals(task.status)))
                .map(task -> task.status)
                .findFirst()
                .orElse(null);
    }

    private Result<String> transition(CloudTaskReportDTO req, String state) {
        CloudTask t = authorizedTask(req == null ? null : req.getDeviceToken(), req == null ? null : req.getTaskId(), req == null ? null : req.getAttemptId(), req == null ? null : req.getLeaseId());
        if (t == null) return Result.unauthorized("task authorization failed");
        if (!state.equals(t.status)) t.status = state;
        if (jdbc != null) {
            jdbc.update("UPDATE task_run SET status=?,finished_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP,error_code=?,error_message=? WHERE run_key=? AND attempt_id=? AND lease_id=?",
                    state, req.getErrorCode(), req.getErrorMessage(), req.getTaskId(), req.getAttemptId(), req.getLeaseId());
        }
        return Result.success("accepted");
    }

    private CloudTask claimPersistedTask(String deviceId) {
        jdbc.update("UPDATE task_run SET status='READY',device_id=NULL,lease_id=NULL,lease_expires_at=NULL,started_at=NULL,updated_at=CURRENT_TIMESTAMP WHERE status='RUNNING' AND lease_expires_at<CURRENT_TIMESTAMP");
        var rows = jdbc.queryForList("SELECT run_key,account_id,attempt_id,config_snapshot FROM task_run WHERE status='READY' ORDER BY created_at LIMIT 1");
        if (rows.isEmpty()) return null;
        var row = rows.get(0);
        String taskId = String.valueOf(row.get("run_key"));
        String attempt = String.valueOf(row.get("attempt_id"));
        String lease = UUID.randomUUID().toString();
        int changed = jdbc.update("UPDATE task_run SET status='RUNNING',device_id=?,lease_id=?,started_at=CURRENT_TIMESTAMP,lease_expires_at=?,updated_at=CURRENT_TIMESTAMP WHERE run_key=? AND status='READY'",
                deviceNumeric(deviceId), lease, LocalDateTime.now().plusMinutes(10), taskId);
        if (changed == 0) return null;
        CloudTask task = new CloudTask(taskId, ((Number) row.get("account_id")).longValue(),
                parseConfig(row.get("config_snapshot")), attempt);
        task.deviceId = deviceId; task.leaseId = lease; task.status = "RUNNING"; task.leaseExpiresAt = LocalDateTime.now().plusMinutes(10);
        tasks.put(taskId, task);
        return task;
    }

    private CloudTask authorizedTask(String token, String taskId, String attemptId, String leaseId) {
        Device d = device(token);
        if (d == null || taskId == null || attemptId == null || leaseId == null) return null;
        CloudTask t = tasks.get(taskId);
        if (t == null && jdbc != null) {
            var rows = jdbc.queryForList(
                    "SELECT account_id,config_snapshot,status FROM task_run WHERE run_key=? AND attempt_id=? AND lease_id=? AND device_id=? AND status='RUNNING' AND lease_expires_at>CURRENT_TIMESTAMP LIMIT 1",
                    taskId, attemptId, leaseId, deviceNumeric(d.id));
            if (!rows.isEmpty()) {
                var row = rows.get(0);
                t = new CloudTask(taskId, ((Number) row.get("account_id")).longValue(),
                        parseConfig(row.get("config_snapshot")), attemptId);
                t.deviceId = d.id;
                t.leaseId = leaseId;
                t.status = String.valueOf(row.get("status"));
                t.leaseExpiresAt = LocalDateTime.now().plusMinutes(1);
                tasks.put(taskId, t);
            }
        }
        if (d == null || t == null || !d.id.equals(t.deviceId) || !eq(attemptId, t.attemptId) || !eq(leaseId, t.leaseId)) return null;
        if (t.leaseExpiresAt != null && t.leaseExpiresAt.isBefore(LocalDateTime.now())) return null;
        return t;
    }
    private Device device(String token) { return token == null ? null : devices.get(sha256(token)); }
    private static boolean eq(String a, String b) { return a != null && a.equals(b); }
    private static boolean blank(String s) { return s == null || s.trim().isEmpty(); }
    private static long deviceNumeric(String id) {
        return Math.abs((long) id.hashCode());
    }
    private static Object parseConfig(Object value) {
        if (value == null) return Map.of();
        if (!(value instanceof String)) return value;
        String text = ((String) value).trim();
        if (text.isEmpty()) return Map.of();
        try {
            Object parsed = GSON.fromJson(text, Object.class);
            return parsed == null ? Map.of() : parsed;
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }
    static String sha256(String value) { try { byte[] d = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); StringBuilder b = new StringBuilder(); for (byte x : d) b.append(String.format("%02x", x)); return b.toString(); } catch (Exception e) { throw new IllegalStateException(e); } }

    static class Device { final String id; String name, version; Integer status; LocalDateTime lastHeartbeat; Device(String id){this.id=id;} }
    public static class CloudTask { public final String taskId, attemptId; public final Long accountId; public final Object config; public volatile String deviceId, leaseId, status="READY"; public LocalDateTime leaseExpiresAt; public final Map<Integer,String> logs = new ConcurrentHashMap<>(); CloudTask(String id, Long a, Object c){this(id,a,c,UUID.randomUUID().toString());} CloudTask(String id, Long a, Object c, String attempt){taskId=id; accountId=a; config=c; attemptId=attempt;} }
}
