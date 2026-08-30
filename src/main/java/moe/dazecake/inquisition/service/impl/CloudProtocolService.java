package moe.dazecake.inquisition.service.impl;

import moe.dazecake.inquisition.model.dto.cloud.*;
import moe.dazecake.inquisition.utils.Result;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CloudProtocolService {
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
        return Result.success("accepted");
    }

    public CloudTask enqueue(String taskId, Long accountId, String config) {
        CloudTask t = new CloudTask(taskId, accountId, config); tasks.put(taskId, t); return t;
    }

    private Result<String> transition(CloudTaskReportDTO req, String state) {
        CloudTask t = authorizedTask(req == null ? null : req.getDeviceToken(), req == null ? null : req.getTaskId(), req == null ? null : req.getAttemptId(), req == null ? null : req.getLeaseId());
        if (t == null) return Result.unauthorized("task authorization failed");
        if (!state.equals(t.status)) t.status = state;
        return Result.success("accepted");
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
    static String sha256(String value) { try { byte[] d = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); StringBuilder b = new StringBuilder(); for (byte x : d) b.append(String.format("%02x", x)); return b.toString(); } catch (Exception e) { throw new IllegalStateException(e); } }

    static class Device { final String id; String name, version; Integer status; LocalDateTime lastHeartbeat; Device(String id){this.id=id;} }
    public static class CloudTask { public final String taskId, attemptId; public final Long accountId; public final String config; public volatile String deviceId, leaseId, status="READY"; public LocalDateTime leaseExpiresAt; public final Map<Integer,String> logs = new ConcurrentHashMap<>(); CloudTask(String id, Long a, String c){taskId=id; accountId=a; config=c; attemptId=UUID.randomUUID().toString();} }
}
