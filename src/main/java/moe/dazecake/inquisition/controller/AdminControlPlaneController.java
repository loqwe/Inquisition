package moe.dazecake.inquisition.controller;

import moe.dazecake.inquisition.annotation.Login;
import moe.dazecake.inquisition.annotation.UserLogin;
import moe.dazecake.inquisition.utils.JWTUtils;
import moe.dazecake.inquisition.utils.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/control")
public class AdminControlPlaneController {
    @Autowired(required = false) private JdbcTemplate jdbc;

    @Login
    @GetMapping("/task-runs")
    public Result<Map<String, Object>> taskRuns(@RequestParam(defaultValue = "1") int current,
                                                @RequestParam(defaultValue = "20") int size) {
        if (jdbc == null) return Result.success(page(current, size, 0, List.of()), "ok");
        int offset = Math.max(0, current - 1) * Math.min(size, 100);
        int limit = Math.min(Math.max(size, 1), 100);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id,account_id,device_id,run_key,attempt_id,status,trigger_type,config_snapshot,lease_expires_at,started_at,finished_at,error_code,error_message,created_at,updated_at FROM task_run ORDER BY created_at DESC LIMIT ? OFFSET ?", limit, offset);
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM task_run", Integer.class);
        return Result.success(page(current, limit, total == null ? 0 : total, rows), "ok");
    }

    @Login
    @GetMapping("/logs")
    public Result<Map<String, Object>> logs(@RequestParam(defaultValue = "1") int current,
                                            @RequestParam(defaultValue = "20") int size,
                                            @RequestParam(required = false) String keyword) {
        if (jdbc == null) return Result.success(page(current, size, 0, List.of()), "ok");
        int offset = Math.max(0, current - 1) * Math.min(size, 100), limit = Math.min(Math.max(size, 1), 100);
        String filter = keyword == null || keyword.isBlank() ? "" : " WHERE a.account LIKE ? OR a.name LIKE ? ";
        String sql = "SELECT l.id,l.level,tr.run_key AS task_type,l.phase AS title,l.message AS detail,NULL AS image_url,CAST(tr.device_id AS CHAR) AS `from`,a.server,a.name,a.account,NULL AS password,l.occurred_at AS time,0 AS `delete` FROM run_log l JOIN task_run tr ON tr.id=l.task_run_id LEFT JOIN account a ON a.id=l.account_id" + filter + " ORDER BY l.occurred_at DESC LIMIT ? OFFSET ?";
        String key = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        List<Map<String, Object>> rows = filter.isEmpty() ? jdbc.queryForList(sql, limit, offset) : jdbc.queryForList(sql, key, key, limit, offset);
        Integer total;
        if (filter.isEmpty()) total = jdbc.queryForObject("SELECT COUNT(*) FROM run_log", Integer.class);
        else total = jdbc.queryForObject("SELECT COUNT(*) FROM run_log l JOIN account a ON a.id=l.account_id" + filter, new Object[]{key, key}, Integer.class);
        return Result.success(page(current, limit, total == null ? 0 : total, rows), "ok");
    }

    @UserLogin
    @GetMapping("/user-logs")
    public Result<Map<String, Object>> userLogs(@RequestHeader("Authorization") String authorization,
                                                @RequestParam(defaultValue = "1") int current,
                                                @RequestParam(defaultValue = "20") int size) {
        if (jdbc == null) return Result.success(page(current, size, 0, List.of()), "ok");
        Long accountId = JWTUtils.getId(authorization);
        if (accountId == null) return Result.unauthorized("invalid token");
        int offset = Math.max(0, current - 1) * Math.min(size, 100), limit = Math.min(Math.max(size, 1), 100);
        String sql = "SELECT l.id,l.level,tr.run_key AS taskType,l.phase AS stage,l.message AS detail,NULL AS imageUrl,a.name,a.account,l.occurred_at AS time FROM run_log l JOIN task_run tr ON tr.id=l.task_run_id JOIN account a ON a.id=l.account_id WHERE l.account_id=? ORDER BY l.occurred_at DESC LIMIT ? OFFSET ?";
        List<Map<String, Object>> rows = jdbc.queryForList(sql, accountId, limit, offset);
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM run_log WHERE account_id=?", Integer.class, accountId);
        return Result.success(page(current, limit, total == null ? 0 : total, rows), "ok");
    }

    private static Map<String, Object> page(int current, int size, int total, List<?> records) {
        Map<String, Object> out = new HashMap<>(); out.put("current", current); out.put("size", size);
        out.put("page", (int) Math.ceil(total / (double) Math.max(size, 1))); out.put("total", total); out.put("records", records); return out;
    }
}
