# 管理员总览与设备运行说明

## 接口契约

管理员登录后通过 `GET /getDashboardOverview` 获取总览。发布后的 JAR 必须同时包含
`DashboardController`、`AdminDashboardOverviewService`、设备运行态投影和任务看板只读快照。

## 设备在线判定

- 后端每 5 分钟执行一次设备离线扫描。
- 任意一次 `/heartBeat` 请求都证明设备到后端的连接仍存活；旧协议中的 `status=0` 只保留给旧 UI 和调度状态使用。
- 只有连续 30 分钟没有任何新心跳，设备运行态才从 `ONLINE` 变为 `OFFLINE`，并触发任务回收。
- 设备运行态保存在 MySQL 的 `device_runtime` 表中。普通容器重启会复用原记录，不会因为重启本身重新制造一轮离线告警。
- 连续任务失败仍会暂停该设备 1 小时；若心跳仍新鲜，不把它误报成掉线设备。

## 重点设备与备用设备

- `IMPORTANT`：参与总览在线/离线统计、设备异常通知和优先任务分配。
- `BACKUP`：保留在设备管理页，不参与总览设备卡片或故障通知。
- 备用设备只要心跳有效、未暂停且满足作用域要求，就可以和重点设备一样接收任务；设备角色只影响总览、故障通知和分组显示。
- 备用设备长期无心跳时保持 `OFFLINE`，不执行 24 小时自动软删除；间隔数周重新发送心跳后可直接恢复 `ONLINE`。
- 历史无角色设备按名称兼容：`A`、`1`、`2` 为重点设备，其余为备用设备。新建设备未指定角色时默认备用。

## 普通重启后的检查

普通重启只检查容器状态和总览接口，不重复执行迁移或全套回归：

```shell
docker inspect inquisition --format 'state={{.State.Status}} restarts={{.RestartCount}}'
curl -ksS https://127.0.0.1:2000/v3/api-docs | grep -o '"/getDashboardOverview"'
```

预期容器为 `running`，并能找到 `/getDashboardOverview`。只有接口缺失、JAR 发生变化、数据库迁移异常或前端再次出现 404/502 时，才进入完整排查。

## 发布验收

- JDK 11：执行 `./gradlew test bootJar`。
- MySQL 8：依次验证设备角色迁移、多时间迁移的首次执行与重复执行。
- 旧客户端：验证 `/heartBeat`、`/getTask`、`/completeTask`、`/failTask`、`/addLog`、`/sanReport` 的旧参数仍可使用。
- 总览：备用设备不计入设备总数、状态卡片和异常列表。
- 回滚：部署前同时保留当前 JAR 与 MySQL 备份。
