# 管理员总览运行说明

## 接口契约

管理员总览前端请求受登录保护的 `GET /getDashboardOverview`。

后端必须包含 `DashboardController`、`AdminDashboardOverviewService` 和运行态投影相关代码。当前可用基线为可靠性分支的 `b465b1e` 或其后续提交。

## 2026-08-03 的 404 原因

面板已发布总览页面，但 129 后端仍停留在缺少总览接口的可靠性提交，导致浏览器请求得到 Spring `404 Not Found`。

修复将总览接口及其依赖的登录统计、任务快照、设备运行态和查询优化并入可靠性分支，同时保留设备心跳的 30 分钟离线判定与旧设备协议兼容。

## 普通重启后的检查

总览接口随 JAR 一起启动，不需要在每次容器重启后重复完整排查或重新执行回归。

仅检查以下两项：

```shell
docker inspect inquisition --format 'state={{.State.Status}} restarts={{.RestartCount}}'
curl -ksS https://127.0.0.1:2000/v3/api-docs | grep -o '"/getDashboardOverview"'
```

预期是容器 `running`，并能输出 `/getDashboardOverview`。只有接口缺失、前端再次出现 404，或 JAR/分支发生变化时，才需要重新做构建、部署和浏览器验收。

## 发布验收基线

- 构建：JDK 11 下执行 `./gradlew test bootJar`。
- 接口：无效令牌访问总览应返回 `401`，而不是 `404`。
- 页面：管理员登录后显示“管理员总览”，且浏览器控制台没有请求错误。
- 回滚：部署前保留 `/root/docker/inquisition/app/Inquisition.jar.bak.manual.<timestamp>`。
