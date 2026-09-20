package moe.dazecake.inquisition.service.impl;

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
    @Autowired(required = false)
    private JdbcTemplate jdbc;
    private final Map<String, Device> devices = new ConcurrentHashMap<>();
    private final Map<String, CloudTask> tasks = new ConcurrentHashMap<>();

    public Result<Map<String, String>> register(CloudRegisterDTO req) {
        if (req == null || blank(req.getDeviceToken())) return Result.paramError("deviceToken required");
        String hash = sha256(req.getDeviceToken());
        Device d = devices.computeIfAbsent(hash, k -> new Device(UUID.randomUUID().toString()));
        d.name = req.getDeviceName(); d.version = req.getClientVersion(); d.lastHeartbeat = LocalDateTime.now();
        return Result.success(Map.of("deviceId", d.id), "registered");
    }

    public Result<Map<String, Object>> heartbeat(CloudHeartbeatDTO req) {
        Device d = device(req == null ? null : req.getDeviceToken());
        if (d == null) return Result.unauthorized("invalid device token");
        d.status = req.getStatus(); d.lastHeartbeat = LocalDateTime.now();
        return Result.success(Map.of("deviceId", d.id, "serverTime", d.lastHeartbeat.toString()), "ok");
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

    public CloudTask enqueue(String taskId, Long accountId, String config) {
        CloudTask t = new CloudTask(taskId, accountId, config); tasks.put(taskId, t);
        if (jdbc != null) {
            jdbc.update("INSERT INTO task_run (account_id,run_key,attempt_id,status,trigger_type,config_snapshot,created_at,updated_at) VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    accountId, taskId, t.attemptId, "READY", "MANUAL", config == null ? "{}" : config);
        }
        return t;
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
        var rows = jdbc.queryForList("SELECT run_key,account_id,attempt_id,config_snapshot FROM task_run WHERE status='READY' ORDER BY created_at LIMIT 1");
        if (rows.isEmpty()) return null;
        var row = rows.get(0);
        String taskId = String.valueOf(row.get("run_key"));
        String attempt = String.valueOf(row.get("attempt_id"));
        String lease = UUID.randomUUID().toString();
        int changed = jdbc.update("UPDATE task_run SET status='RUNNING',device_id=?,lease_id=?,started_at=CURRENT_TIMESTAMP,lease_expires_at=?,updated_at=CURRENT_TIMESTAMP WHERE run_key=? AND status='READY'",
                deviceNumeric(deviceId), lease, LocalDateTime.now().plusMinutes(10), taskId);
        if (changed == 0) return null;
        CloudTask task = new CloudTask(taskId, ((Number) row.get("account_id")).longValue(), String.valueOf(row.get("config_snapshot")), attempt);
        task.deviceId = deviceId; task.leaseId = lease; task.status = "RUNNING"; task.leaseExpiresAt = LocalDateTime.now().plusMinutes(10);
        tasks.put(taskId, task);
        return task;
    }

    private CloudTask authorizedTask(String token, String taskId, String attemptId, String leaseId) {
        Device d = device(token); CloudTask t = taskId == null ? null : tasks.get(taskId);
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
    static String sha256(String value) { try { byte[] d = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); StringBuilder b = new StringBuilder(); for (byte x : d) b.append(String.format("%02x", x)); return b.toString(); } catch (Exception e) { throw new IllegalStateException(e); } }

    static class Device { final String id; String name, version; Integer status; LocalDateTime lastHeartbeat; Device(String id){this.id=id;} }
    public static class CloudTask { public final String taskId, attemptId; public final Long accountId; public final String config; public volatile String deviceId, leaseId, status="READY"; public LocalDateTime leaseExpiresAt; public final Map<Integer,String> logs = new ConcurrentHashMap<>(); CloudTask(String id, Long a, String c){this(id,a,c,UUID.randomUUID().toString());} CloudTask(String id, Long a, String c, String attempt){taskId=id; accountId=a; config=c; attemptId=attempt;} }
}
