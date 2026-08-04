# Discord Worker 图床

审判庭后端通过 Cloudflare Worker 把日志截图上传到 Discord 私有频道。设备继续调用原有 `/uploadImage`，不需要更新 Lua 脚本，也不会接触 Discord Bot Token。

## 调用链

1. 设备向审判庭 `/uploadImage` 提交 Base64 图片和设备 Token。
2. 审判庭验证设备后解码图片，以 multipart 字段 `image` 调用 Worker 的 `POST /upload`。
3. 审判庭使用独立的上传密钥完成 Worker 鉴权。
4. Worker 上传到 Discord，并返回稳定的 `/file/{messageId}/{attachmentId}` 地址。
5. 审判庭把该 Worker 地址原样返回给旧设备。

## 前置条件

- Cloudflare Worker 已部署并完成 `/health`、上传和回读验证。
- Worker 已配置 `BOT_TOKEN`、`UPLOAD_TOKEN` 和 Discord 频道 ID。
- 后端的 `DISCORD_IMAGE_UPLOAD_TOKEN` 必须与 Worker 的 `UPLOAD_TOKEN` 完全一致。
- 后端不需要 Discord 账号密码、Bot Token 或 Webhook URL。

## 后端配置

通过容器环境变量注入：

```dotenv
DISCORD_IMAGE_STORAGE_ENABLE=true
DISCORD_IMAGE_WORKER_URL=https://inquisition-img.longxin.xyz
DISCORD_IMAGE_UPLOAD_TOKEN=与Worker一致的上传密钥
DISCORD_IMAGE_MAX_BYTES=8388608
```

`DISCORD_IMAGE_WORKER_URL` 只能填写 Worker 的 HTTPS 根地址，不能包含路径、查询参数或认证信息。`DISCORD_IMAGE_MAX_BYTES` 不应超过 Worker 的 `MAX_FILE_BYTES`；当前后端默认 8 MiB，Worker 默认 10 MiB。

Docker Compose 示例：

```yaml
services:
  inquisition:
    environment:
      DISCORD_IMAGE_STORAGE_ENABLE: "true"
      DISCORD_IMAGE_WORKER_URL: https://inquisition-img.longxin.xyz
      DISCORD_IMAGE_UPLOAD_TOKEN: ${DISCORD_IMAGE_UPLOAD_TOKEN}
      DISCORD_IMAGE_MAX_BYTES: 8388608
```

上传密钥只应放在 Compose 使用的 `.env`、Secret 管理器或容器环境变量中，不要写入仓库、镜像、命令历史或日志。启用图床但地址、密钥或大小限制无效时，后端会拒绝启动。

## 兼容行为

成功上传时，旧 `/uploadImage` 响应中的 `data` 类似：

```text
https://inquisition-img.longxin.xyz/file/123456789012345678/234567890123456789
```

历史上已经保存的 `/media/discord/{messageId}/{attachmentId}` 地址仍可访问，后端会用 `302` 重定向到对应 Worker 地址。新上传直接返回 Worker 地址，减少一次后端跳转。

上传是非幂等操作，后端不会自动重试失败的 `POST /upload`，避免在 Discord 中创建重复消息。Worker 不可用、鉴权失败或响应格式异常时，设备会收到上传失败结果，不会得到伪造成功地址。

## 验证

1. 请求 Worker `/health`，确认 HTTP 200 且 `configured` 为 `true`。
2. 用一台已登记设备调用原 `/uploadImage`，确认 HTTP 200 且响应 `data` 为 Worker `/file/...` 地址。
3. 回读 `data`，确认 HTTP 200、`Content-Type` 为图片类型，内容与上传图片一致。
4. 查看 Discord 私有频道，确认只新增一条对应附件消息。
5. 检查后端日志，确认没有上传密钥或 Bot Token。

## 回滚

将 `DISCORD_IMAGE_STORAGE_ENABLE` 设为 `false` 并重启后端，即可恢复原有 COS/CHFS 选择逻辑。回滚不会影响已经生成的 Worker `/file/...` 地址；只要 Worker 和 Discord 消息仍存在，这些地址仍可读取。
